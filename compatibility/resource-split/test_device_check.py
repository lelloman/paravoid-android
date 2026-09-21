from pathlib import Path
import runpy
import unittest

validate = runpy.run_path(str(Path(__file__).with_name('device-check.py')))['validate']


class DeviceAssertions(unittest.TestCase):
    def setUp(self):
        self.result = dict(run='fresh', title='Payload A', shell=True, shellLabel='Resource split probe',
                           pinnedTheme=True, libraryLoader=True, titleId=0x7f040003, pid=123,
                           constructorTitle='Payload A', removed='Removed after A', added='absent',
                           view='Payload A', italian='Risorse A', library='Library resource',
                           styleable='Library resource', payloadTheme=True, accent=0xff006600 - 2**32)
        self.observed = dict(providerTitle='Payload A', applicationTitle='Payload A',
                             providerPid='123', providerBeforeApplication='true')

    def verify(self):
        validate('paravoidAndroid', 'A', self.result, self.observed, 'fresh')

    def test_valid_shell_report(self):
        self.verify()

    def test_stale_run_rejected(self):
        self.result['run'] = 'stale'
        with self.assertRaises(AssertionError): self.verify()

    def test_late_or_wrong_provider_rejected(self):
        for key, value in dict(providerTitle='absent', providerPid='122', providerBeforeApplication='false').items():
            with self.subTest(key=key):
                old = self.observed[key]
                self.observed[key] = value
                with self.assertRaises(AssertionError): self.verify()
                self.observed[key] = old

    def test_early_constructor_lookup_required(self):
        self.result['constructorTitle'] = 'absent'
        with self.assertRaises(AssertionError): self.verify()

    def test_all_runtime_lookup_checks_required(self):
        for key, value in dict(pinnedTheme=False, libraryLoader=False, payloadTheme=False,
                               styleable='wrong', italian='wrong', view='wrong', titleId=0x80040003).items():
            with self.subTest(key=key):
                old = self.result[key]
                self.result[key] = value
                with self.assertRaises(AssertionError): self.verify()
                self.result[key] = old

    def test_no_pack_does_not_allow_installed_fallback(self):
        self.result.update(title='absent', constructorTitle='absent', removed='absent')
        self.observed.update(providerTitle='absent', applicationTitle='absent')
        validate('paravoidAndroid', 'none', self.result, self.observed, 'fresh')
        self.result['removed'] = 'Removed after A'
        with self.assertRaises(AssertionError):
            validate('paravoidAndroid', 'none', self.result, self.observed, 'fresh')


if __name__ == '__main__': unittest.main()
