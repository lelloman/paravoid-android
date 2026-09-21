import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('migration_driver', Path(__file__).with_name('device-check.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class MigrationGuards(unittest.TestCase):
    def value(self, phase='recover', version=2):
        return dict(run='run', phase=phase, version=version, passed=True, databaseVersion=2,
                    rows=3, migrations=0)

    def test_valid_recovery(self):
        driver.verify(self.value(), 'run', 2, 'recover')

    def test_wrong_code_or_schema_version_rejected(self):
        for change in (dict(version=1), dict(databaseVersion=1)):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify(dict(self.value(), **change), 'run', 2, 'recover')

    def test_lost_rows_or_repeated_migration_rejected(self):
        for change in (dict(rows=0), dict(rows=2), dict(migrations=1)):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify(dict(self.value(), **change), 'run', 2, 'recover')

    def test_downgrade_must_be_rejected_for_expected_reason(self):
        value = dict(self.value('downgrade', 1), downgradeRejected=True, reason='migration from 2 to 1 missing')
        driver.verify(value, 'run', 1, 'downgrade')
        for change in (dict(downgradeRejected=False), dict(reason='some unrelated failure')):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify(dict(value, **change), 'run', 1, 'downgrade')

    def test_failed_transaction_or_constraint_check_rejected(self):
        value = dict(self.value('upgrade'), migrations=1, transactionRollback=True, uniqueIndex=True)
        driver.verify(value, 'run', 2, 'upgrade')
        for change in (dict(transactionRollback=False), dict(uniqueIndex=False), dict(migrations=0)):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify(dict(value, **change), 'run', 2, 'upgrade')

    def test_errors_and_wrong_run_rejected(self):
        for change in (dict(error='failure'), dict(run='stale'), dict(passed=False)):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify(dict(self.value(), **change), 'run', 2, 'recover')

    def test_stale_request_ignored(self):
        with patch.object(driver, 'adb', return_value='<map><string name="result">{"request":"old"}</string></map>'):
            self.assertIsNone(driver.report('app', 'new'))


if __name__ == '__main__':
    unittest.main()
