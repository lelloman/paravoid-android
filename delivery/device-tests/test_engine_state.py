import unittest
from engine_state import controls, properties, state, OPERATIONS


class EngineStateTest(unittest.TestCase):
    def test_reads_explicit_choices_from_unified_record(self):
        calls = []
        def adb(*args):
            calls.append(args)
            return '# state\nversion=2\npreferences.checks=false\nkind=NONE\n'
        self.assertEqual(state(adb, 'fixture')['preferences.checks'], 'false')
        self.assertEqual(calls[0][-1], OPERATIONS)

    def test_controls_refuses_unresolved_alias(self):
        calls = []
        def adb(*args):
            calls.append(args)
            return 'other/App'
        with self.assertRaises(AssertionError):
            controls(adb, 'fixture')
        self.assertEqual(len(calls), 1)

    def test_controls_starts_exact_resolved_alias(self):
        calls = []
        component = 'fixture/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'
        def adb(*args):
            calls.append(args)
            return component + '\n'
        controls(adb, 'fixture')
        self.assertEqual(calls[-1], ('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', component))

    def test_ui_uses_enabled_current_retry_action(self):
        from controls_ui import tap
        calls = []
        xml = '<hierarchy><node text="Check again" enabled="false" bounds="[0,0][100,50]"/><node text="Retry now" enabled="true" bounds="[0,50][100,100]"/></hierarchy>'
        tap(lambda *args: calls.append(args), lambda: xml, 'Check now')
        self.assertEqual(calls, [('shell', 'input', 'tap', '50', '75')])


if __name__ == '__main__':
    unittest.main()
