import unittest
from controls_access import resolved_updates_alias


class ControlsAccessTest(unittest.TestCase):
    APP = 'com.lelloman.paravoidcompat.complete.paravoid'
    ALIAS = APP + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'

    def test_exact_resolved_alias_selected(self):
        self.assertEqual(self.ALIAS, resolved_updates_alias(
            '2 activities found:\n  ' + self.ALIAS + '\nother.app/.Launcher\n', self.APP))

    def test_missing_alias_preserves_shortcut_fallback(self):
        for output in ('', 'No activities found', self.APP + '/.LauncherActivity'):
            with self.subTest(output=output):
                self.assertIsNone(resolved_updates_alias(output, self.APP))

    def test_substrings_and_other_package_not_selected(self):
        for output in ('Error: ' + self.ALIAS, self.ALIAS + 'Extra',
                       'other.app/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'):
            with self.subTest(output=output):
                self.assertIsNone(resolved_updates_alias(output, self.APP))


if __name__ == '__main__':
    unittest.main()
