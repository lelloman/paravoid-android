#!/usr/bin/env python3
"""Reissue existing real-app component bytes with throwaway fixture signing only.

This does not edit any installed state or bypass the production verifier. It is
for avoiding identical-code rebuilds after allocating a negative-test version.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', type=int, required=True)
    parser.add_argument('--key', type=Path, required=True)
    args = parser.parse_args()
    assert args.version > 0 and not args.output.exists()
    key = serialization.load_der_private_key(args.key.read_bytes(), None)
    args.output.mkdir(parents=True)
    with zipfile.ZipFile(args.source / 'payload.vpk') as original:
        release = json.loads(base64.b64decode(json.loads(original.read('release.json'))['body']))
        assert args.version > release['payloadVersion']
        release.update(payloadVersion=args.version, releaseId='p' + str(args.version))
        body = json.dumps(release, sort_keys=True, separators=(',', ':')).encode()
        envelope = json.dumps(dict(keyId='release', body=base64.b64encode(body).decode(),
            signature=base64.b64encode(key.sign(b'paravoid/v1/release\n' + body,
                padding.PKCS1v15(), hashes.SHA256())).decode()), sort_keys=True, separators=(',', ':')).encode()
        (args.output / 'release.json').write_bytes(envelope)
        with zipfile.ZipFile(args.output / 'payload.vpk', 'w', compression=zipfile.ZIP_STORED) as target:
            for entry in original.infolist():
                if entry.filename == 'release.json':
                    target.writestr(entry, envelope)
                else:
                    with original.open(entry) as source, target.open(entry, 'w') as destination:
                        shutil.copyfileobj(source, destination, 65536)
    with zipfile.ZipFile(args.source / 'payload.vpk') as before, zipfile.ZipFile(args.output / 'payload.vpk') as after:
        for entry in before.namelist():
            if entry != 'release.json':
                with before.open(entry) as left, after.open(entry) as right:
                    assert hashlib.file_digest(left, 'sha256').digest() == hashlib.file_digest(right, 'sha256').digest()
    print(f'Reissued payload {args.version}; every non-manifest entry has identical SHA-256')


if __name__ == '__main__':
    main()
