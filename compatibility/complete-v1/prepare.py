#!/usr/bin/env python3
"""Generate throwaway fixture keys; never developer/publisher credentials."""
import base64
import argparse
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parent
KEYS = ROOT / 'build/keys'

def prepare():
    KEYS.mkdir(parents=True, exist_ok=True)
    trust = dict(version=1, applicationId='com.lelloman.paravoidcompat.complete.paravoid',
                 minimumPayloadVersion=1, minimumHeadRevision=1)
    for role in ('release', 'head', 'grant'):
        path = KEYS / (role + '.der')
        if not path.exists():
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            path.write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
            path.chmod(0o600)
        key = serialization.load_der_private_key(path.read_bytes(), None)
        public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        trust[role + 'Keys'] = {role: base64.b64encode(public).decode('ascii')}
    (KEYS / 'trust.json').write_text(json.dumps(trust, sort_keys=True, separators=(',', ':')) + '\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pressure-mib', type=int, choices=(0, 32, 1000), default=0,
                        help='Generate an ignored incompressible asset for disk-write fault tests; 0 removes it')
    args = parser.parse_args()
    prepare()
    pressure = ROOT / 'build/pressure-assets/paravoid-pressure.bin'
    if args.pressure_mib:
        pressure.parent.mkdir(parents=True, exist_ok=True)
        block = os.urandom(1024 * 1024)
        with pressure.open('wb') as out:
            for _ in range(args.pressure_mib):
                out.write(block)
    else:
        pressure.unlink(missing_ok=True)
