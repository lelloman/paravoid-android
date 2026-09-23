"""Fixed-shell A-to-B delivery and user-confirmed restart with an A worker lease."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Server


def run(args, app, adb, nodes, tap, old_main, old_worker):
    def texts():
        return '\n'.join(n.attrib.get('text', '') for n in nodes())
    def wait_text(expected):
        for _ in range(30):
            state = texts()
            if expected in state:
                return state
            time.sleep(.25)
        raise AssertionError('Missing ' + expected + ': ' + state)
    apk = adb('shell', 'pm', 'path', app).strip().removeprefix('package:')
    assert apk.endswith('/base.apk') and '\n' not in apk
    installed_hash = adb('shell', 'sha256sum', apk).split()[0]
    with tempfile.TemporaryDirectory(prefix='paravoid-worker-policy-') as tmp:
        local = Path(tmp) / 'shell.apk'
        adb('pull', apk, str(local))
        with zipfile.ZipFile(local) as contents:
            policy = json.loads(contents.read('assets/paravoid/shell-policy.json'))
    assert policy['descriptor']['distribution']['authentication'] == 'public'
    archive = args.pending_update / 'payload.vpk'
    envelope = (args.pending_update / 'release.json').read_bytes()
    release = json.loads(base64.b64decode(json.loads(envelope)['body']))
    assert release['shellContractId'] == policy['contractId'] and release['payloadVersion'] == 2
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    now = int(time.time())
    head = dict(version=1, applicationId=app, shellContractId=policy['contractId'], channel='stable',
                sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1, headRevision=2,
                issuedAt=now, expiresAt=now + 3600, status='available',
                release=dict(releaseId=release['releaseId'], payloadVersion=2,
                             manifestSha256=hashlib.sha256(envelope).hexdigest(),
                             archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(), archiveSize=archive.stat().st_size))
    body = json.dumps(head, sort_keys=True, separators=(',', ':')).encode()
    key = serialization.load_der_private_key((ROOT / 'compatibility/complete-v1/build/keys/head.der').read_bytes(), None)
    signed = json.dumps(dict(keyId='head', body=base64.b64encode(body).decode(),
                            signature=base64.b64encode(key.sign(b'paravoid/v1/head\n' + body, padding.PKCS1v15(), hashes.SHA256())).decode())).encode()
    catalog = Catalog()
    catalog.add_head(app, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk),
                              abis=','.join(abis), runtime='1', format='1', protocol='1'), signed)
    catalog.add_archive(app, release['releaseId'], archive)
    server = Server(('127.0.0.1', args.server_port), catalog)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    recovery = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
    try:
        adb('reverse', 'tcp:18765', 'tcp:' + str(args.server_port))
        tap('Check now')
        state = wait_text('Pending: ' + release['releaseId'])
        assert 'payload 2' in state
        assert adb('shell', 'pidof', app).strip() == old_main
        assert adb('shell', 'pidof', app + ':worker').strip() == old_worker
        assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
        before = adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection').split()[0]
        tap('Restart app…'); tap('Cancel')
        assert adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection').split()[0] == before
        assert adb('shell', 'pidof', app).strip() == old_main
        assert adb('shell', 'pidof', app + ':worker').strip() == old_worker
        print('PASS: signed B stays pending while A main/worker survive; cancel preserves selection and both PIDs', flush=True)
        # Activation must not require another request, APK replacement or adb kill.
        server.shutdown(); server.server_close()
        tap('Restart app…'); tap('Stop and restart')
        wait_text('generation=B;asset=payload-asset;java=payload-java-resource')
        assert adb('shell', 'pidof', app).strip() != old_main
        worker = subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', app + ':worker'],
                                text=True, capture_output=True, timeout=45)
        assert not worker.stdout.strip(), 'Old non-sticky worker survived restart'
        assert adb('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery
        assert 'generation=B' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
        assert adb('shell', 'pidof', app + ':worker').strip() != old_worker
        assert adb('shell', 'sha256sum', apk).split()[0] == installed_hash
        print('PASS: confirmed offline restart activates B, preserves recovery; new worker loads B; installed shell unchanged', args.serial, flush=True)
    finally:
        server.shutdown(); server.server_close()
        adb('reverse', '--remove', 'tcp:18765')
