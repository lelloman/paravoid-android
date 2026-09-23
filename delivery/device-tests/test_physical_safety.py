import unittest
from physical_safety import CONSENT, PACKAGES, preflight, validate_apk_badging


class PhysicalSafetyTest(unittest.TestCase):
    def setUp(self):
        self.values = {
            ('get-state',): 'device',
            ('shell', 'getprop', 'ro.kernel.qemu'): '0',
            ('shell', 'getprop', 'ro.boot.qemu'): '',
            ('shell', 'getprop', 'ro.product.cpu.abi'): 'arm64-v8a',
            ('shell', 'uname', '-m'): 'aarch64',
            ('shell', 'getprop', 'ro.build.version.sdk'): '35',
            ('shell', 'pm', 'list', 'users'): 'Users:\n UserInfo{0:Owner:13} running',
            ('shell', 'pm', 'list', 'packages', '-u'): 'package:android\npackage:com.android.settings',
            ('reverse', '--list'): '',
        }

    def run_gate(self, serial='physical-serial', consent=CONSENT):
        return preflight(lambda *args: self.values[args], serial, consent)

    def test_read_only_supported_device(self):
        self.assertEqual(35, self.run_gate())

    def test_apk_identity_and_release(self):
        valid = "package: name='" + PACKAGES[0] + "' versionCode='1'\n"
        validate_apk_badging(valid, PACKAGES[0])
        for text in ('', "package: name='other.app'", valid + 'application-debuggable\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_apk_badging(text, PACKAGES[0])

    def test_requires_explicit_serial_and_consent(self):
        for serial in ('', '-d', 'has space', 'emulator-5586'):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                self.run_gate(serial=serial)
        for consent in (None, '', PACKAGES[0]):
            with self.subTest(consent=consent), self.assertRaises(ValueError):
                self.run_gate(consent=consent)

    def test_refuses_unsafe_or_unknown_platform(self):
        changes = [
            (('get-state',), 'offline'),
            (('shell', 'getprop', 'ro.kernel.qemu'), '1'),
            (('shell', 'getprop', 'ro.boot.qemu'), '1'),
            (('shell', 'getprop', 'ro.product.cpu.abi'), 'x86_64'),
            (('shell', 'uname', '-m'), 'x86_64'),
            (('shell', 'getprop', 'ro.build.version.sdk'), '28'),
            (('shell', 'pm', 'list', 'users'), 'Users:\nUserInfo{0:Owner:13}\nUserInfo{10:Work:30}'),
            (('shell', 'pm', 'list', 'packages', '-u'), ''),
            (('shell', 'pm', 'list', 'packages', '-u'), 'Error: unavailable'),
            (('reverse', '--list'), 'UsbFfs tcp:18765 tcp:18766'),
        ]
        for key, value in changes:
            with self.subTest(key=key):
                original = self.values[key]
                self.values[key] = value
                with self.assertRaises(ValueError):
                    self.run_gate()
                self.values[key] = original

    def test_refuses_existing_or_retained_fixture_data(self):
        key = ('shell', 'pm', 'list', 'packages', '-u')
        for package in PACKAGES:
            self.values[key] = 'package:android\npackage:' + package
            with self.subTest(package=package), self.assertRaises(ValueError):
                self.run_gate()

    def test_adb_failure_propagates_before_mutation(self):
        def failed(*args):
            raise RuntimeError('adb failed')
        with self.assertRaises(RuntimeError):
            preflight(failed, 'physical-serial', CONSENT)


if __name__ == '__main__':
    unittest.main()
