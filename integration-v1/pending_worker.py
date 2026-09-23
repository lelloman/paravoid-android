"""Fixed-shell A-to-B delivery and user-confirmed restart with an A worker lease."""
import base64
import hashlib
import importlib.util
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
                sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1, headRevision=args.head_revision,
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
    gate = None
    forward = None
    try:
        adb('reverse', 'tcp:18765', 'tcp:' + str(args.server_port))
        if args.journal_death:
            record = ROOT / 'paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/AtomicRecord.java'
            lines = [number for number, line in enumerate(record.read_text().splitlines(), 1)
                     if 'fault.at("' + args.journal_death + '")' in line]
            assert len(lines) == 1, 'Journal boundary source changed'
            forward = adb('forward', 'tcp:0', 'jdwp:' + recovery).strip()
            assert forward.isdigit()
            gate_dir = tempfile.TemporaryDirectory(prefix='paravoid-journal-death-')
            markers = Path(gate_dir.name)
            subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', gate_dir.name,
                            str(ROOT / 'integration-v1/JournalDeathGate.java')], check=True, timeout=45)
            gate_log = (markers / 'log').open('w')
            gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', gate_dir.name,
                                     'JournalDeathGate', forward, gate_dir.name,
                                     args.journal_death, str(lines[0])], stdout=gate_log, stderr=gate_log)
            def wait_marker(name):
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if (markers / name).exists(): return
                    if gate.poll() is not None:
                        raise AssertionError('Journal gate exited: ' + (markers / 'log').read_text())
                    time.sleep(.1)
                raise AssertionError('Journal gate timed out: ' + (markers / 'log').read_text())
            wait_marker('ready')
            store = 'no_backup/paravoid-v1/'
            decoder_spec = importlib.util.spec_from_file_location('fixture_device_check',
                           ROOT / 'compatibility/complete-v1/device-check.py')
            decoder = importlib.util.module_from_spec(decoder_spec)
            decoder_spec.loader.exec_module(decoder)
            def selection_state():
                raw = subprocess.check_output(['adb', '-s', args.serial, 'exec-out', 'run-as', app,
                                               'cat', store + 'selection'], timeout=45)
                return decoder.Record(raw).selection()
            state_before = selection_state()
            assert state_before['active']['version'] == 1 and state_before['pending'] is None
            selection_before = adb('shell', 'run-as', app, 'sha256sum', store + 'selection').split()[0]
            security_before = adb('shell', 'run-as', app, 'sha256sum', store + 'security').split()[0]
            active = store + 'generations/' + state_before['active']['directory'] + '/archive.vpk'
            active_before = adb('shell', 'run-as', app, 'sha256sum', active).split()[0]
        tap('Check now')
        if args.journal_death:
            wait_marker('reached')
            assert gate.wait(timeout=15) == 0, (markers / 'log').read_text()
            server.shutdown(); server.server_close()
            for _ in range(30):
                current = subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof',
                                          app + ':paravoid_recovery'], text=True, capture_output=True,
                                         timeout=10).stdout.strip()
                if current != recovery: break
                time.sleep(.2)
            else: raise AssertionError('Original recovery process survived debugger exit')
            selection_after = adb('shell', 'run-as', app, 'sha256sum', store + 'selection').split()[0]
            committed = args.journal_death != 'content-synced'
            assert (selection_after != selection_before) == committed, 'Wrong atomic-selection side'
            state_after = selection_state()
            assert state_after['active'] == state_before['active']
            assert (state_after['pending'] is not None) == committed
            if committed: assert state_after['pending']['release'] == release['releaseId']
            security_after = adb('shell', 'run-as', app, 'sha256sum', store + 'security').split()[0]
            assert security_after != security_before, 'Signed head admission should advance security before publication'
            assert adb('shell', 'run-as', app, 'sha256sum', active).split()[0] == active_before
            assert adb('shell', 'pidof', app).strip() == old_main
            assert adb('shell', 'pidof', app + ':worker').strip() == old_worker
            assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
            print('PASS: installed signed B journal process death at ' + args.journal_death
                  + '; selection ' + ('committed' if committed else 'unchanged')
                  + ', admitted security floor and active A bytes/processes preserved', args.serial, flush=True)
            return
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
        if args.sticky_worker:
            from sticky_worker import run as restart_sticky
            restart_sticky(args, app, adb, nodes, tap, old_main, old_worker,
                           expected_generation='B', require_launch=True)
            assert adb('shell', 'sha256sum', apk).split()[0] == installed_hash
            print('PASS: signed pending B activates offline in Activity and naturally respawned sticky worker; shell unchanged', args.serial, flush=True)
            return
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
        if gate is not None and gate.poll() is None:
            gate.terminate(); gate.wait(timeout=10)
        if forward is not None: adb('forward', '--remove', 'tcp:' + forward)
        if args.journal_death:
            gate_log.close(); gate_dir.cleanup()
