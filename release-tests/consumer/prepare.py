"""Generate ignored, throwaway consumer-fixture signing material, never release credentials."""
import json
from pathlib import Path
import base64
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

directory = Path(__file__).resolve().parent / 'build/keys'
directory.mkdir(parents=True, exist_ok=True)
path = directory / 'release.der'
if not path.exists():
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    path.write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()))
    path.chmod(0o600)
key = serialization.load_der_private_key(path.read_bytes(), None)
public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,
                         serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
(directory / 'trust.json').write_text(json.dumps(dict(version=1,
    applicationId='com.lelloman.paravoidreleaseconsumer.paravoid', minimumPayloadVersion=1,
    minimumHeadRevision=1, releaseKeys={'fixture': public}, headKeys={}, grantKeys={})))
