from pathlib import Path
import runpy
import unittest

probe = runpy.run_path(str(Path(__file__).with_name('pack-resources.py')))
PREFIX = probe['PACKAGE'] + ':'


class LedgerChecks(unittest.TestCase):
    def setUp(self):
        self.a = {PREFIX + 'string/shell_label': 0x7f010000,
                  PREFIX + 'string/removed': 0x7f010001}
        self.b = {**self.a, PREFIX + 'string/a_added': 0x7f010002}
        self.pinned = {PREFIX + 'string/shell_label': 0x7f010000}

    def validate(self):
        probe['validate_ids'](self.a, self.b, self.pinned)

    def test_valid_append_only_ids(self):
        self.validate()

    def test_reuse_of_retired_id_rejected(self):
        self.b[PREFIX + 'string/a_added'] = self.a[PREFIX + 'string/removed']
        with self.assertRaises(AssertionError): self.validate()

    def test_changed_existing_id_rejected(self):
        self.b[PREFIX + 'string/shell_label'] += 4
        with self.assertRaises(AssertionError): self.validate()

    def test_changed_pinned_id_rejected(self):
        self.pinned[PREFIX + 'string/shell_label'] += 4
        with self.assertRaises(AssertionError): self.validate()

    def test_wrong_package_id_rejected(self):
        self.b[PREFIX + 'string/a_added'] = 0x80010002
        with self.assertRaises(AssertionError): self.validate()


if __name__ == '__main__': unittest.main()
