#!/usr/bin/env python3
"""Disposable build-test keys only; never use these as production publisher keys."""
import argparse
import base64
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    policy = dict(version=1, applicationId=args.application_id, minimumPayloadVersion=1, minimumHeadRevision=1)
    for role in ('release', 'head', 'grant'):
        path = args.output / (role + '.der')
        if not path.exists():
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as stream:
                stream.write(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
        key = serialization.load_der_private_key(path.read_bytes(), None)
        public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        policy[role + 'Keys'] = {role: base64.b64encode(public).decode('ascii')}
    (args.output / 'trust.json').write_text(json.dumps(policy, sort_keys=True, separators=(',', ':')) + '\n')

if __name__ == '__main__':
    main()
