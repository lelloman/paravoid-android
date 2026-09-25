import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from signing import Publisher


class SigningTest(unittest.TestCase):
    def test_renewal_survives_restart_and_preserves_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            (root / "key.der").write_bytes(key.private_bytes(serialization.Encoding.DER,
                serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            archive = b"unchanged signed archive fixture"
            (root / "payload.vpk").write_bytes(archive)
            config = dict(applicationId="example.app", shellContractId="a" * 64, keyId="head",
                privateKeyFile="key.der", releases=[dict(file="payload.vpk", releaseId="r42",
                payloadVersion=42, archiveSize=len(archive), archiveSha256=hashlib.sha256(archive).hexdigest(),
                manifestSha256="b" * 64)])
            clock = [1000000]
            publisher = Publisher(config, root, root / "state", clock=lambda: clock[0])
            first = publisher.announcement()
            self.assertEqual(first, publisher.announcement())
            clock[0] += 3 * 86400
            restarted = Publisher(config, root, root / "state", clock=lambda: clock[0])
            renewed = restarted.announcement()
            self.assertNotEqual(first, renewed)
            def verify(envelope):
                envelope = json.loads(envelope)
                body = base64.b64decode(envelope["body"])
                key.public_key().verify(base64.b64decode(envelope["signature"]),
                    b"paravoid/v1/feed\n" + body, padding.PKCS1v15(), hashes.SHA256())
                return json.loads(body)
            a, b = verify(first), verify(renewed)
            self.assertEqual(a["releases"], b["releases"])
            self.assertEqual(a["headRevision"] + 1, b["headRevision"])
            self.assertGreater(b["expiresAt"], clock[0])
            self.assertEqual(archive, (root / "payload.vpk").read_bytes())


if __name__ == "__main__":
    unittest.main()
