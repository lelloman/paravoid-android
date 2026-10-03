import hashlib
from pathlib import Path
import tempfile
import unittest
from acceptance import GATES, audit

SHA = 'a' * 40


class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        log = self.base / 'evidence.log'
        log.write_text('fixture evidence only')
        self.plan = {'version': 1, 'gates': {name: {
            'status': 'passed', 'revision': SHA, 'command': 'fixture command',
            'platform': 'fixture platform', 'recordedBy': 'fixture owner',
            'evidence': [{'path': log.name, 'sha256': hashlib.sha256(log.read_bytes()).hexdigest()}]
        } for name in GATES}}
        self.plan['gates']['physicalArm64']['deviceType'] = 'physical-arm64'
        self.plan['gates']['authenticatedApp']['workflows'] = ['login', 'content', 'playback', 'native', 'update', 'repair']
        self.plan['gates']['security']['reviewType'] = 'independent'
        self.plan['gates']['remoteConsumer']['repositoryUrl'] = 'https://example.invalid/fixture'

    def test_complete_records_never_approve_release(self):
        result = audit(self.plan, SHA, self.base)
        self.assertTrue(result['evidenceComplete'])
        self.assertFalse(result['releaseApproved'])

    def test_missing_gates_fail(self):
        result = audit({'version': 1, 'gates': {}}, SHA, self.base)
        self.assertEqual(len(result['gaps']), len(GATES))

    def test_stale_source_fails_every_gate(self):
        self.assertEqual(len(audit(self.plan, 'b' * 40, self.base)['gaps']), len(GATES))

    def test_tampered_or_missing_log_fails(self):
        (self.base / 'evidence.log').write_text('changed')
        self.assertEqual(len(audit(self.plan, SHA, self.base)['gaps']), len(GATES))
        (self.base / 'evidence.log').unlink()
        self.assertEqual(len(audit(self.plan, SHA, self.base)['gaps']), len(GATES))

    def test_emulator_self_review_and_local_repository_are_insufficient(self):
        self.plan['gates']['physicalArm64']['deviceType'] = 'emulator-arm64'
        self.plan['gates']['security']['reviewType'] = 'internal'
        self.plan['gates']['remoteConsumer']['repositoryUrl'] = 'file:///tmp/staging'
        self.assertEqual({g['gate'] for g in audit(self.plan, SHA, self.base)['gaps']},
                         {'physicalArm64', 'security', 'remoteConsumer'})

    def test_incomplete_workflows_and_unattributed_records_fail(self):
        self.plan['gates']['authenticatedApp']['workflows'] = ['login']
        self.plan['gates']['installed'].pop('recordedBy')
        self.assertEqual(len(audit(self.plan, SHA, self.base)['gaps']), 2)

    def test_invalid_plan_or_abbreviated_commit_is_refused(self):
        for plan, revision in ((self.plan, 'abcdef'), ({'version': 2, 'gates': {}}, SHA)):
            with self.assertRaises(ValueError):
                audit(plan, revision, self.base)


if __name__ == '__main__':
    unittest.main()
