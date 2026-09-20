import unittest
from device_process import kill_fixture_process


class ProcessDeathTest(unittest.TestCase):
    app = "com.lelloman.paravoidcompat.test.normal"

    def fake_adb(self, *, deny=False, sdk="29", debug="1", pids=("123", "123")):
        calls = []
        remaining = iter(pids)

        def adb(*args, **kwargs):
            calls.append(args)
            if args[1] == "pidof":
                return next(remaining)
            if args[1] == "getprop":
                return sdk if args[2] == "ro.build.version.sdk" else debug
            if args[1] == "run-as" and deny:
                raise RuntimeError("run-as kill denied")
            return ""

        return adb, calls

    def test_run_as_remains_default(self):
        adb, calls = self.fake_adb()
        kill_fixture_process(adb, "emulator-5584", self.app, "123")
        self.assertEqual(calls[-1], ("shell", "run-as", self.app, "kill", "-9", "123"))
        self.assertEqual(len(calls), 2)

    def test_debug_api29_emulator_fallback_rechecks_pid(self):
        adb, calls = self.fake_adb(deny=True)
        kill_fixture_process(adb, "emulator-5584", self.app, "123")
        self.assertEqual(calls[-2], ("shell", "pidof", self.app))
        self.assertEqual(calls[-1], ("shell", "su", "0", "kill", "-9", "123"))

    def test_fallback_refuses_other_devices_or_changed_pid(self):
        for serial, config in (("phone", {}), ("emulator-5584", {"sdk": "28"}),
                               ("emulator-5584", {"debug": "0"}),
                               ("emulator-5584", {"pids": ("123", "456")})):
            with self.subTest(serial=serial, config=config):
                adb, calls = self.fake_adb(deny=True, **config)
                with self.assertRaises(RuntimeError):
                    kill_fixture_process(adb, serial, self.app, "123")
                self.assertFalse(any(c[1] == "su" for c in calls))

    def test_changed_initial_pid_is_never_killed(self):
        adb, calls = self.fake_adb(pids=("456",))
        with self.assertRaises(RuntimeError):
            kill_fixture_process(adb, "emulator-5584", self.app, "123")
        self.assertEqual(len(calls), 1)

    def test_invalid_target_never_calls_adb(self):
        for app, pid in (("other.app", "123"), (self.app, "-1"),
                         (self.app, "0"), (self.app, "1"), (self.app, "123 456")):
            with self.subTest(app=app, pid=pid):
                adb, calls = self.fake_adb()
                with self.assertRaises(ValueError):
                    kill_fixture_process(adb, "emulator-5584", app, pid)
                self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
