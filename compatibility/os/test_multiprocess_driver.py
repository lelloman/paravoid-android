import copy
from pathlib import Path
import runpy
import unittest

driver = runpy.run_path(str(Path(__file__).with_name('multiprocess-device-check.py')))


class MultiprocessAssertions(unittest.TestCase):
    def setUp(self):
        self.result = dict.fromkeys(('remote', 'uid', 'loader', 'clientLoader', 'text', 'callback',
                                    'list', 'null', 'exception', 'bundleRawRejected', 'bundlePrepared'), True)
        self.result.update(pid=202, peerPid=101, run='fresh', instance='service')
        self.worker = dict(startupPid='202', startupProcess='test.app:worker', startupCount='1',
                           startupLoader='true', startupInstance='application', binderActivityCreated='false',
                           binderBinding='fresh', binderBindingInstance='service')

    def verify(self):
        driver['validate']('test.app', self.result, self.worker, '101', '202')

    def test_valid_remote_connection(self):
        self.verify()

    def test_every_wire_control_required(self):
        for key, value in self.result.copy().items():
            if value is True:
                with self.subTest(key=key):
                    self.result[key] = False
                    with self.assertRaises(AssertionError):
                        self.verify()
                    self.result[key] = True

    def test_wrong_process_or_initialization_rejected(self):
        for key, value in dict(startupPid='303', startupProcess='test.app', startupCount='2',
                               startupLoader='false', startupInstance='', binderActivityCreated='true',
                               binderBinding='stale', binderBindingInstance='old-service').items():
            with self.subTest(key=key):
                original = copy.copy(self.worker)
                self.worker[key] = value
                with self.assertRaises(AssertionError):
                    self.verify()
                self.worker = original

    def test_wrong_client_pid_rejected(self):
        self.result['peerPid'] = 999
        with self.assertRaises(AssertionError):
            self.verify()

    def test_in_process_shortcut_rejected(self):
        self.result['peerPid'] = 202
        with self.assertRaises(AssertionError):
            driver['validate']('test.app', self.result, self.worker, '202', '202')


if __name__ == '__main__':
    unittest.main()
