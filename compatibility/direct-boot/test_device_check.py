import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('direct_boot_driver', Path(__file__).with_name('device-check.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DirectBootGuards(unittest.TestCase):
    def test_reboot_requires_valid_new_id_not_transport_error(self):
        previous = '11111111-1111-1111-1111-111111111111'
        for current in ('', previous, 'adb: device offline'):
            with self.subTest(current=current), patch.object(driver, 'adb', side_effect=['1', current]):
                self.assertFalse(driver.boot_changed(previous))
        with patch.object(driver, 'adb', side_effect=['1', '22222222-2222-2222-2222-222222222222']):
            self.assertTrue(driver.boot_changed(previous))
        with patch.object(driver, 'adb', return_value='0'):
            self.assertFalse(driver.boot_changed(previous))

    def test_locked_event(self):
        driver.verify_locked(dict(unlocked=False, noActivity=True, loader=True, boot=2, count=1), 1)

    def test_unlocked_activity_wrong_loader_repeated_or_old_boot_rejected(self):
        base = dict(unlocked=False, noActivity=True, loader=True, boot=2, count=1)
        for change in (dict(unlocked=True), dict(noActivity=False), dict(loader=False), dict(count=2), dict(boot=1)):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                driver.verify_locked(dict(base, **change), 1)

    def test_stale_run_and_other_package_ignored(self):
        raw = '\n'.join(['{"app":"app","run":"old","kind":"locked"}',
                         '{"app":"other","run":"new","kind":"locked"}'])
        with patch.object(driver, 'adb', return_value=raw):
            self.assertIsNone(driver.observation('app', 'new', 'locked'))

    def test_same_run_previous_boot_ignored(self):
        raw = '{"app":"app","run":"run","kind":"initialized","boot":1}'
        with patch.object(driver, 'adb', return_value=raw):
            self.assertIsNone(driver.observation('app', 'run', 'initialized', 2))
            self.assertEqual(1, driver.observation('app', 'run', 'initialized', 1)['boot'])

    def test_any_current_run_error_rejected(self):
        raw = '{"app":"app","run":"run","kind":"error","error":"CE access"}'
        with patch.object(driver, 'adb', return_value=raw), self.assertRaises(AssertionError):
            driver.observation('app', 'run', 'alarm')

    def test_latest_duplicate_not_hidden(self):
        raw = '\n'.join(['--------- log buffer', '{"app":"app","run":"run","kind":"locked","count":1}',
                         '{"app":"app","run":"run","kind":"locked","count":2}'])
        with patch.object(driver, 'adb', return_value=raw):
            self.assertEqual(2, driver.observation('app', 'run', 'locked')['count'])


if __name__ == '__main__':
    unittest.main()
