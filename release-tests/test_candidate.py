import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from candidate import audit, config_check, MODULES, MARKERS


def decisions():
    return dict(version='1.2.3-test', licenseName='Fixture license',
                licenseUrl='https://example.invalid/license', repositoryUrl='https://example.invalid/maven',
                artifactSigning='none-approved', requireJavadoc=False)


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)

    def fixture(self):
        c = decisions()
        coords = [('com.lelloman.paravoid', name) for name in MODULES]
        coords += [(name, name + '.gradle.plugin') for name in MARKERS]
        for group, name in coords:
            directory = self.repo / group.replace('.', '/') / name / c['version']
            directory.mkdir(parents=True)
            stem = directory / (name + '-' + c['version'])
            Path(str(stem) + '.pom').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
                <groupId>{group}</groupId><artifactId>{name}</artifactId><version>{c['version']}</version>
                <licenses><license><name>{c['licenseName']}</name><url>{c['licenseUrl']}</url></license></licenses></project>''')
            if name in MODULES:
                extension = '.aar' if name in ('paravoid-api', 'paravoid-runtime') else '.jar'
                for suffix in (extension, '-sources.jar'):
                    with zipfile.ZipFile(str(stem) + suffix, 'w') as z:
                        z.writestr('fixture.txt', 'test')

    def test_every_owner_choice_required(self):
        for key in decisions():
            c = decisions()
            del c[key]
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'Missing owner decision'):
                config_check(c)

    def test_unsafe_or_unapproved_decisions(self):
        for key, value in [('version', '../escape'), ('version', '0.1.0-dev'),
                           ('version', '1-SNAPSHOT'), ('repositoryUrl', 'http://example.invalid'),
                           ('repositoryUrl', 'https://user:secret@example.invalid'),
                           ('repositoryUrl', 'https://example.invalid/?token=secret'),
                           ('artifactSigning', 'whatever'), ('requireJavadoc', 'false')]:
            c = decisions()
            c[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                config_check(c)

    def test_complete_local_candidate_and_hashes(self):
        self.fixture()
        result = audit(decisions(), self.repo)
        self.assertEqual([], result['failures'])
        self.assertEqual(23, len(result['artifacts']))
        self.assertTrue(all(len(a['sha256']) == 64 for a in result['artifacts']))

    def test_missing_metadata_and_artifacts(self):
        self.fixture()
        pom = next(self.repo.rglob('*.pom'))
        pom.write_text(pom.read_text().replace('Fixture license', 'Wrong license'))
        next(self.repo.rglob('*-sources.jar')).unlink()
        failures = audit(decisions(), self.repo)['failures']
        self.assertTrue(any('license name' in f for f in failures))
        self.assertTrue(any('sources.jar' in f for f in failures))

    def test_javadoc_and_external_signing_fail_closed(self):
        self.fixture()
        c = decisions()
        c.update(requireJavadoc=True, artifactSigning='external-openpgp')
        failures = audit(c, self.repo)['failures']
        self.assertTrue(any('-javadoc.jar' in f for f in failures))
        self.assertTrue(any('.asc' in f for f in failures))
        self.assertTrue(any('fingerprint' in f for f in failures))

    def test_corrupt_archive(self):
        self.fixture()
        next(self.repo.rglob('*-sources.jar')).write_bytes(b'not a zip')
        self.assertTrue(any('Invalid archive' in f for f in audit(decisions(), self.repo)['failures']))

    def test_cli_refuses_missing_decisions_without_effects(self):
        config = self.repo / 'config.json'
        config.write_text('{}')
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('candidate.py')),
                                 '--config', str(config), '--repository', str(self.repo)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('Missing owner decision', result.stderr)
        self.assertEqual([config], list(self.repo.iterdir()))


if __name__ == '__main__':
    unittest.main()
