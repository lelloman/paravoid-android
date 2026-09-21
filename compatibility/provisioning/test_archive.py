import io
import unittest
import warnings
import zipfile

from archive_profile import pack, stored_zip, verify_archive, MAX_ENTRY, MAX_TOTAL
from signed_profile import new_key


class ArchiveTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = new_key()

    def setUp(self):
        self.identity = dict(applicationId="example.app", contract="test", releaseId="r1", payloadVersion=1)
        self.components = {"assets/message.txt": ("asset", b"hello"), "code/classes.dex": ("code", b"opaque fixture bytes")}
        self.blob = pack(self.identity, self.components, self.key)

    def verify(self, blob):
        return verify_archive(blob, self.identity, self.key.public_key())

    def entries(self):
        with zipfile.ZipFile(io.BytesIO(self.blob)) as archive:
            return [(info.filename, archive.read(info)) for info in archive.infolist()]

    def test_valid_components(self):
        self.assertEqual(self.verify(self.blob), 2)

    def test_tampered_missing_unexpected_duplicate(self):
        entries = self.entries()
        cases = [entries[:-1], entries + [("assets/extra.txt", b"extra")],
                 entries[:-1] + [(entries[-1][0], b"tampered")], entries + [entries[-1]]]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            for case in cases:
                with self.assertRaises((ValueError, KeyError)):
                    self.verify(stored_zip(case))

    def test_wrong_identity_and_signer(self):
        with self.assertRaises(ValueError):
            verify_archive(self.blob, dict(self.identity, contract="wrong"), self.key.public_key())
        from cryptography.exceptions import InvalidSignature
        with self.assertRaises(InvalidSignature):
            verify_archive(self.blob, self.identity, new_key().public_key())

    def test_unsafe_paths_and_roles(self):
        for path in ("../escape", "/absolute", "assets/../escape", "assets\\escape", "assets//empty"):
            with self.assertRaises(ValueError):
                pack(self.identity, {path: ("asset", b"x")}, self.key)
        with self.assertRaises(ValueError):
            pack(self.identity, {"assets/wrong": ("native", b"x")}, self.key)

    def test_component_and_aggregate_bounds(self):
        with self.assertRaises(ValueError):
            pack(self.identity, {"assets/large": ("asset", b"x" * (MAX_ENTRY + 1))}, self.key)
        with self.assertRaises(ValueError):
            pack(self.identity, {f"assets/{i}": ("asset", b"x" * MAX_ENTRY) for i in range(MAX_TOTAL // MAX_ENTRY + 1)}, self.key)


if __name__ == "__main__":
    unittest.main()
