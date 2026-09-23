"""Reference HTTP fixture: hold signed forward repair until a retry dialog is open."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import threading
import time
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Server, Handler


class RepairServer:
    def __init__(self, device, shell, output, port):
        app = 'com.lelloman.paravoidcompat.complete.paravoid'
        with zipfile.ZipFile(shell) as apk:
            policy = json.loads(apk.read('assets/paravoid/shell-policy.json'))
        envelope = (output / 'release.json').read_bytes()
        release = json.loads(base64.b64decode(json.loads(envelope)['body']))
        assert release['payloadVersion'] == 3 and release['shellContractId'] == policy['contractId']
        archive = output / 'payload.vpk'
        sdk = int(device.run('shell', 'getprop', 'ro.build.version.sdk').strip())
        abis = device.run('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
        now = int(time.time())
        body = dict(version=1, applicationId=app, shellContractId=policy['contractId'], channel='stable',
            sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1, headRevision=1, issuedAt=now,
            expiresAt=now + 3600, status='available', release=dict(releaseId=release['releaseId'],
                payloadVersion=3, manifestSha256=hashlib.sha256(envelope).hexdigest(),
                archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(), archiveSize=archive.stat().st_size))
        encoded = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
        key = serialization.load_der_private_key((ROOT / 'compatibility/complete-v1/build/keys/head.der').read_bytes(), None)
        signature = key.sign(b'paravoid/v1/head\n' + encoded, padding.PKCS1v15(), hashes.SHA256())
        head = json.dumps(dict(keyId='head', body=base64.b64encode(encoded).decode(),
                               signature=base64.b64encode(signature).decode())).encode()
        catalog = Catalog('public')
        catalog.add_archive(app, release['releaseId'], archive)
        catalog.add_head(app, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk),
            abis=','.join(abis), runtime='1', format='1', protocol='1'), head)
        self.allowed = threading.Event()
        allowed = self.allowed

        class GatedHandler(Handler):
            def do_GET(self):
                if not allowed.wait(180):
                    self.send_error(503)
                    return
                super().do_GET()

        self.device = device
        self.server = Server(('127.0.0.1', port), catalog)
        self.server.RequestHandlerClass = GatedHandler
        device.run('reverse', 'tcp:18765', 'tcp:' + str(port))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.allowed.set()
        self.server.shutdown()
        self.server.server_close()
        self.device.run('reverse', '--remove', 'tcp:18765', check=False)
