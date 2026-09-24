import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name('changes.py')
spec = importlib.util.spec_from_file_location('paravoid_changes', MODULE)
changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changes)

NOTES = '''# Changelog

## [Unreleased]

### Added

- Next feature.

## [0.3.0] - 2026-09-24

### Changed

- New controls.

### Migration

- Replace old control call.

## [0.2.0] - 2026-09-23

### Fixed

- Earlier fix.

### Migration

- No app changes.

## [0.1.0] - 2026-09-22

### Added

- First release.

### Migration

- Initial integration.
'''


class ChangesTest(unittest.TestCase):
    def test_skipped_versions_include_every_intermediate_release(self):
        result = changes.changes_between(NOTES, '0.1.0', '0.3.0')
        self.assertIn('New controls.', result)
        self.assertIn('Earlier fix.', result)
        self.assertNotIn('First release.', result)
        self.assertNotIn('Next feature.', result)
        self.assertLess(result.index('New controls.'), result.index('Earlier fix.'))

    def test_unknown_and_backward_ranges_fail_closed(self):
        for previous, target in [('0.1.0', '0.1.0'), ('0.3.0', '0.2.0'),
                                 ('missing', '0.3.0'), ('0.1.0', 'missing')]:
            with self.subTest(previous=previous, target=target):
                with self.assertRaises(ValueError):
                    changes.changes_between(NOTES, previous, target)

    def test_v_prefixed_jitpack_versions_resolve_to_same_notes(self):
        result = changes.changes_between(NOTES, 'v0.1.0', 'v0.3.0')
        self.assertIn('New controls.', result)
        self.assertEqual('0.3.0', changes.resolve_version('v0.3.0', ['0.3.0']))

    def test_released_notes_require_date_and_migration(self):
        for source in [NOTES.replace('## [0.3.0] - 2026-09-24', '## [0.3.0]'),
                       NOTES.replace('2026-09-24', '2026-02-30'),
                       NOTES.replace('### Migration\n\n- Replace old control call.\n', ''),
                       NOTES.replace('### Migration\n\n- Replace old control call.\n', '### Migration\n')]:
            with self.assertRaises(ValueError):
                changes.sections(source)

    def test_current_changelog_is_valid_unreleased_document(self):
        entries = changes.sections((MODULE.parent.parent / 'CHANGELOG.md').read_text())
        self.assertEqual('Unreleased', entries[0][0])


if __name__ == '__main__':
    unittest.main()
