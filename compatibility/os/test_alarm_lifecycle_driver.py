"""Host assertion guards; no adb/device required."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    'alarm_lifecycle_driver', Path(__file__).with_name('alarm-lifecycle-device-check.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class LifecycleGuards(unittest.TestCase):
    def test_pending_counts_only_active_headers_for_exact_package(self):
        text = '''
 ELAPSED_WAKEUP #0: Alarm{abc type 2 origWhen 100 whenElapsed 100 com.lelloman.paravoidcompat.os.normal}
 ELAPSED_WAKEUP #1: Alarm{def type 2 origWhen 100 whenElapsed 100 com.lelloman.paravoidcompat.os.normal.other}
 tag=*walarm*:com.lelloman.paravoidcompat.os.normal/AlarmReceiver
 historical com.lelloman.paravoidcompat.os.normal
'''
        with patch.object(driver, 'adb', return_value=text):
            self.assertEqual(1, driver.pending('com.lelloman.paravoidcompat.os.normal'))
            self.assertEqual(0, driver.pending('comXlelloman.paravoidcompat.os.normal'))

    def test_stale_run_not_accepted(self):
        with patch.object(driver, 'report', return_value={'key': json.dumps({'run': 'old'})}):
            self.assertIsNone(driver.event('app', 'key', 'new'))

    def test_errors_never_look_like_missing_events(self):
        for values in ({'lifecycleError': 'failure'}, {'alarm_error': '{}'},
                       {'key': json.dumps({'error': 'failure'})}):
            with self.subTest(values=values), patch.object(driver, 'report', return_value=values):
                with self.assertRaises(AssertionError):
                    driver.event('app', 'key', 'run')

    def test_received_requires_cold_activity_free_payload_and_single_delivery(self):
        base = {'run': 'run', 'parcel': True, 'loader': True, 'noActivity': True}
        for field in ('parcel', 'loader', 'noActivity'):
            bad = dict(base, **{field: False})
            with self.subTest(field=field), patch.object(driver, 'event', return_value=bad):
                with self.assertRaises(AssertionError):
                    driver.received('app', 'kind', 'run')
        with patch.object(driver, 'event', return_value=base), \
                patch.object(driver, 'report', return_value={'alarmCount_kind': '2'}):
            with self.assertRaises(AssertionError):
                driver.received('app', 'kind', 'run')

    def test_successful_event(self):
        value = {'run': 'run', 'parcel': True, 'loader': True, 'noActivity': True}
        with patch.object(driver, 'event', return_value=value), \
                patch.object(driver, 'report', return_value={'alarmCount_kind': '1'}):
            self.assertEqual(value, driver.received('app', 'kind', 'run'))


if __name__ == '__main__':
    unittest.main()
