import bz2
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('dvpk', Path(__file__).with_name('dvpk.py'))
dvpk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dvpk)


def integer(value):
    return (abs(value) | ((1 << 63) if value < 0 else 0)).to_bytes(8, 'little')


def bsdiff(records, diff, extra, size):
    blocks = [bz2.compress(b''.join(integer(x) for record in records for x in record)), bz2.compress(diff), bz2.compress(extra)]
    return b'BSDIFF40' + integer(len(blocks[0])) + integer(len(blocks[1])) + integer(size) + b''.join(blocks)


def frame(records, diff, extra, size):
    blocks = [dvpk.deflate(b''.join(struct.pack('<qqq', *record) for record in records)), dvpk.deflate(diff), dvpk.deflate(extra)]
    return dvpk.MAGIC + struct.pack('<qqq', len(blocks[0]), len(blocks[1]), size) + b''.join(blocks)


class DeltaInteropTest(unittest.TestCase):
    def decode(self, base, target, patch, succeeds=True, existing=False):
        classes = os.environ.get('DELIVERY_TEST_CLASSES')
        if not classes:
            self.skipTest('Run delivery/test.sh for Java interoperability')
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            paths = [directory / name for name in ('base', 'target', 'patch', 'output')]
            for path, data in zip(paths, (base, target, patch)):
                path.write_bytes(data)
            if existing:
                paths[3].write_bytes(b'keep')
            result = subprocess.run(['java', '-Xmx32m', '-cp', classes,
                'com.lelloman.paravoidandroid.contract.DeltaDecodeMain', *map(str, paths)], capture_output=True)
            if succeeds:
                self.assertEqual(0, result.returncode, result.stderr.decode())
                self.assertEqual(target, paths[3].read_bytes())
            else:
                self.assertNotEqual(0, result.returncode)
                if existing:
                    self.assertEqual(b'keep', paths[3].read_bytes())
                else:
                    self.assertFalse(paths[3].exists(), 'Failed decoder retained output')

    def test_bsdiff_conversion_and_negative_seek(self):
        base, target = b'abcd', b'abXYZab'
        patch = dvpk.from_bsdiff(bsdiff([(2, 3, -2), (2, 0, 0)], b'\0'*4, b'XYZ', len(target)))
        self.assertEqual(target, dvpk.apply(base, patch))
        self.decode(base, target, patch)

    def test_outside_base_is_zero_and_addition_wraps(self):
        base = bytes([255, 2])
        patch = frame([(1, 0, -2), (3, 0, 0)], bytes([2, 4, 3, 5]), b'', 4)
        target = bytes([1, 4, 2, 7])
        self.assertEqual(target, dvpk.apply(base, patch))
        self.decode(base, target, patch)

    def test_literal_stream_and_chunk_boundaries(self):
        target = bytes(range(256))*100
        patch = frame([(0, len(target), 0)], b'', target, len(target))
        self.decode(b'base', target, patch)

    def test_truncation_trailing_compressed_and_unconsumed_data(self):
        valid = frame([(0, 3, 0)], b'', b'new', 3)
        for patch in (valid[:-1], valid+b'x', frame([(0, 3, 0)], b'x', b'new', 3),
                      frame([(0, 3, 0), (0, 0, 1)], b'', b'new', 3)):
            with self.subTest(patch=patch):
                self.decode(b'old', b'new', patch, False)

    def test_control_bounds_and_seek_overflow(self):
        for records in ([(-1, 4, 0)], [(0, 4, 0)], [(0, 0, 0)], [(1, 0, (1 << 63)-1), (2, 0, 0)]):
            self.decode(b'old', b'new', frame(records, b'\0'*3, b'new', 3), False)

    def test_target_mismatch_and_existing_destination(self):
        patch = frame([(0, 3, 0)], b'', b'bad', 3)
        self.decode(b'old', b'new', patch, False)
        patch = frame([(0, 3, 0)], b'', b'new', 3)
        self.decode(b'old', b'new', patch, False, existing=True)

    def test_header_lengths_and_magic(self):
        valid = frame([(0, 3, 0)], b'', b'new', 3)
        for patch in (b'WRONG001'+valid[8:], valid[:8]+struct.pack('<q', (1 << 63)-1)+valid[16:],
                      valid[:24]+struct.pack('<q', 4)+valid[32:]):
            self.decode(b'old', b'new', patch, False)

    def test_operation_limit_and_empty_target(self):
        self.decode(b'old', b'x', frame([(0, 0, 1)]*(dvpk.MAX_OPERATIONS+1), b'', b'', 1), False)
        self.decode(b'old', b'', frame([], b'', b'', 0), False)

    def test_reference_rejects_partial_controls_and_garbage(self):
        with self.assertRaises(ValueError):
            dvpk.from_bsdiff(b'not a BSDIFF patch')
        with self.assertRaises(ValueError):
            dvpk.apply(b'old', frame([(0, 3, 0)], b'', b'new', 3)+b'garbage')


if __name__ == '__main__':
    unittest.main()
