#!/usr/bin/env python3
"""Audit recorded release evidence locally; never approve, publish, or use a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re

GATES = ('host', 'installed', 'physicalArm64', 'authenticatedApp', 'security', 'remoteConsumer')


def audit(plan, revision, base):
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Supply the full source commit SHA')
    if plan.get('version') != 1 or not isinstance(plan.get('gates'), dict):
        raise ValueError('Expected version 1 release evidence plan')
    gaps = []
    for name in GATES:
        gate = plan['gates'].get(name, {})
        reasons = []
        if gate.get('status') != 'passed':
            reasons.append(gate.get('reason') or 'No passing evidence recorded')
        else:
            if gate.get('revision') != revision:
                reasons.append('Evidence is for a different source revision')
            for key in ('command', 'platform', 'recordedBy'):
                if not isinstance(gate.get(key), str) or not gate[key].strip():
                    reasons.append('Missing ' + key)
            evidence = gate.get('evidence', [])
            if not isinstance(evidence, list) or not evidence:
                reasons.append('Missing retained evidence files')
            else:
                for entry in evidence:
                    if not isinstance(entry, dict) or not isinstance(entry.get('path'), str):
                        reasons.append('Invalid evidence file record'); continue
                    path = base / entry['path']
                    if not path.is_file():
                        reasons.append('Missing evidence file: ' + entry['path'])
                    elif hashlib.sha256(path.read_bytes()).hexdigest() != entry.get('sha256'):
                        reasons.append('Evidence hash mismatch: ' + entry['path'])
            if name == 'physicalArm64' and gate.get('deviceType') != 'physical-arm64':
                reasons.append('Physical ARM64 evidence is required')
            if name == 'authenticatedApp' and gate.get('workflows') != ['login', 'content', 'playback', 'native', 'update', 'repair']:
                reasons.append('Record the full authenticated workflow matrix')
            if name == 'security' and gate.get('reviewType') != 'independent':
                reasons.append('Independent protocol/signing review is required')
            if name == 'remoteConsumer' and not gate.get('repositoryUrl', '').startswith('https://'):
                reasons.append('Actual remote HTTPS consumer evidence is required')
        if reasons:
            gaps.append({'gate': name, 'reasons': reasons})
    return {'sourceRevision': revision, 'evidenceComplete': not gaps,
            'releaseApproved': False, 'gaps': gaps,
            'scope': 'Checks evidence records and hashes only; owner approval and release metadata remain separate.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    try:
        report = audit(json.loads(args.plan.read_text()), args.revision, args.plan.parent)
    except (ValueError, OSError, TypeError) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2))
    return 0 if report['evidenceComplete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
