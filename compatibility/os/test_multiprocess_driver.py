import copy
import contextlib
import io
from pathlib import Path
import runpy
import unittest
from unittest.mock import Mock, patch

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

    def check_receiver_restoration(self, cleanup_failure):
        command = Mock(return_value='')
        cleanup = Mock(side_effect=RuntimeError('cleanup failed') if cleanup_failure else None)
        check = driver['check_mode']
        with patch.dict(check.__globals__, {
            'install': lambda mode: 'test.app', 'adb': command, 'cleanup': cleanup,
            'wait_for': Mock(side_effect=RuntimeError('connection failed')),
        }), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'cleanup failed' if cleanup_failure else 'connection failed'):
                check('normal', 'peer')
        receiver = 'test.app/com.lelloman.paravoidcompat.os.AlarmLifecycleReceiver'
        command.assert_any_call('shell', 'run-as', 'test.app', 'pm', 'disable', '--user', '0', receiver)
        self.assertEqual(command.call_args.args,
                         ('shell', 'run-as', 'test.app', 'pm', 'default-state', '--user', '0', receiver))
        cleanup.assert_called_once_with('test.app')

    def test_receiver_restored_after_test_failure(self):
        self.check_receiver_restoration(False)

    def test_receiver_restored_even_if_cleanup_fails(self):
        self.check_receiver_restoration(True)


if __name__ == '__main__':
    unittest.main()
