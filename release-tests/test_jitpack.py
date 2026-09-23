"""Check install argument safety without invoking Gradle or remote services."""
import os
from pathlib import Path
import subprocess
import unittest

SCRIPT = Path(__file__).with_name('jitpack-install.sh')


class JitPackInstallTests(unittest.TestCase):
    def run_install(self, **values):
        env = {k: v for k, v in os.environ.items()
               if not k.startswith('PARAVOID_') and k not in ('GROUP', 'ARTIFACT', 'VERSION')}
        env.update(ANDROID_HOME='/unused/test-sdk', PARAVOID_GRADLE='/bin/echo')
        env.update(values)
        return subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True)

    def test_missing_or_unsafe_version_refused(self):
        for version in ('', '../bad', '-Pbad', 'two words', 'line\nbreak'):
            result = self.run_install(VERSION=version)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('publishToMavenLocal', result.stdout)

    def test_jitpack_environment_and_all_included_builds(self):
        result = self.run_install(GROUP='com.github.owner', ARTIFACT='project', VERSION='v0.1.0-alpha01')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('-PparavoidGroup=com.github.owner.project', result.stdout)
        self.assertIn('-PparavoidVersion=v0.1.0-alpha01', result.stdout)
        for module in ('api', 'contract', 'runtime', 'gradle-plugin', 'hilt', 'work'):
            self.assertIn(':paravoid-' + module + ':publishToMavenLocal', result.stdout)

    def test_explicit_local_overrides(self):
        result = self.run_install(PARAVOID_GROUP='com.github.lelloman.paravoid-android',
            PARAVOID_VERSION='test-commit', PARAVOID_OFFLINE='true',
            PARAVOID_MAVEN_LOCAL='/tmp/isolated-maven')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('-Dmaven.repo.local=/tmp/isolated-maven', result.stdout)
        self.assertIn('--offline', result.stdout)

    def test_unsafe_group_refused(self):
        result = self.run_install(VERSION='test', PARAVOID_GROUP='../escape')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('publishToMavenLocal', result.stdout)
