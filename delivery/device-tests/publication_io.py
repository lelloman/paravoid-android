"""Installed signed p1-to-p2 publication IO, live reader and contending worker checks."""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def run(root, serial, app, archive, catalog, query, head, server, adb, ui, tap, mode='io'):
    assert mode in ('io', 'death', 'cancel')
    assert archive.stat().st_size < 32 * 1048576, 'Use the ordinary fixture for publication IO'
    spec = importlib.util.spec_from_file_location('publication_device', root / 'compatibility/complete-v1/device-check.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    device = module.Device(serial, adb('emu', 'avd', 'name').splitlines()[0])
    store = 'no_backup/paravoid-v1/'

    def data(path):
        return subprocess.check_output(['adb', '-s', serial, 'exec-out', 'run-as', app, 'cat', path], timeout=45)

    def sign(role, body):
        key = serialization.load_der_private_key((root / ('compatibility/complete-v1/build/keys/' + role + '.der')).read_bytes(), None)
        body = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
        signature = key.sign(('paravoid/v1/' + role + '\n').encode() + body, padding.PKCS1v15(), hashes.SHA256())
        return json.dumps(dict(keyId=role, body=base64.b64encode(body).decode(), signature=base64.b64encode(signature).decode())).encode()

    def await_status(text):
        device.await_(lambda: text in ui(), text)

    main_pid = adb('shell', 'pidof', app).strip()
    owner = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
    assert main_pid.isdigit() and owner.isdigit() and main_pid != owner
    assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
    worker = adb('shell', 'pidof', app + ':worker').strip()
    assert worker.isdigit() and worker not in (owner, main_pid)

    def reserve():
        result = adb('shell', 'content', 'call', '--uri', 'content://' + app + '.worker',
                     '--method', 'probe.reserve-space', '--arg', str(archive.stat().st_size))
        assert 'pid=' + worker in result
        return result

    assert 'result=ADMITTED' in reserve()
    initial = device.state()
    assert initial['active']['version'] == 1 and initial['pending'] is None
    active = store + 'generations/' + initial['active']['directory'] + '/archive.vpk'
    digest = hashlib.sha256(data(active)).digest()
    gate = None
    port = adb('forward', 'tcp:0', 'jdwp:' + owner).strip()
    try:
        with tempfile.TemporaryDirectory(prefix='paravoid-publication-io-') as tmp:
            markers = Path(tmp)
            candidate = markers / 'p2.vpk'
            # Reissue the real Android components with a new signed release identity;
            # production verification/admission must still accept every byte.
            with zipfile.ZipFile(archive) as original:
                release = json.loads(base64.b64decode(json.loads(original.read('release.json'))['body']))
                release.update(releaseId='p2', payloadVersion=2)
                envelope = sign('release', release)
                with zipfile.ZipFile(candidate, 'w', compression=zipfile.ZIP_STORED) as output:
                    for entry in original.infolist():
                        if entry.filename == 'release.json': output.writestr(entry.filename, envelope)
                        else:
                            with original.open(entry) as source, output.open(entry.filename, 'w') as target:
                                shutil.copyfileobj(source, target, 65536)
            update = copy.deepcopy(head)
            update['headRevision'] = 2
            update['release'] = dict(releaseId='p2', payloadVersion=2, manifestSha256=hashlib.sha256(envelope).hexdigest(),
                archiveSha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), archiveSize=candidate.stat().st_size)
            catalog.add_archive(app, 'p2', candidate)
            replacement = type(catalog)(catalog.mode, catalog.prefix)
            replacement.add_head(app, query, sign('head', update))
            catalog.heads.update(replacement.heads)
            source = root / 'paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/SelectionJournal.java'
            lines = [i for i, line in enumerate(source.read_text().splitlines(), 1) if 'try { record.write(encode(state)); }' in line]
            assert len(lines) == 1
            subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', tmp,
                            str(root / 'delivery/device-tests/SelectionWriteGate.java')], check=True, timeout=45)
            with (markers / 'log').open('w') as log:
                gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', tmp,
                    'SelectionWriteGate', port, tmp, str(lines[0]), mode], stdout=log, stderr=log)

                def wait(name):
                    deadline = time.monotonic() + 40
                    while not (markers / name).exists():
                        assert gate.poll() is None and time.monotonic() < deadline, (markers / 'log').read_text()
                        time.sleep(.1)

                wait('ready'); tap('Check now'); wait('publishing')
                assert 'result=UNAVAILABLE' in reserve(), 'Worker bypassed p2 publication ownership'
                selection, security = data(store + 'selection'), data(store + 'security')
                assert device.state() == initial
                if mode == 'io':
                    # Real owner-permission failure, not an injected exception.
                    # This exact fixture directory stays readable; no other app is touched.
                    adb('shell', 'run-as', app, 'chmod', '500', store)
                    (markers / 'resume').touch(); wait('denied')
                    adb('shell', 'run-as', app, 'chmod', '700', store)
                elif mode == 'death':
                    (markers / 'kill').touch(); wait('dead')
                else:
                    tap('Cancel download')
                    assert len(data('no_backup/paravoid-update-preferences.cancel')) == 36
                    assert device.state() == initial
                    (markers / 'resume').touch()
                assert gate.wait(timeout=10) == 0
            if mode == 'io': await_status('Update status: IO')
            elif mode == 'death':
                device.await_(lambda: not device.run('shell', 'pidof', app + ':paravoid_recovery', check=False).strip(), 'publication owner death')
            else:
                await_status('Update: CANCELLED')
                assert device.state()['pending']['version'] == 2, 'Late cancellation must not undo committed staging'
                assert device.state()['active'] == initial['active']
            assert data(store + 'security') == security
            if mode != 'cancel':
                assert data(store + 'selection') == selection and device.state() == initial
            assert hashlib.sha256(data(active)).digest() == digest
            assert adb('shell', 'pidof', app).strip() == main_pid
            assert adb('shell', 'pidof', app + ':worker').strip() == worker
            assert not adb('shell', 'run-as', app, 'find', 'no_backup', '-name', 'paravoid-update-preferences.retry').strip()
            assert 'result=ADMITTED' in reserve(), 'Failed publication leaked writer ownership'
            print('PASS: signed distinct p2 publication ' + mode + '; p1 reader/worker/security and bytes survive; competing owner excluded then admitted', serial, flush=True)
            if mode == 'death':
                import sys
                subprocess.run([sys.executable, str(root / 'integration-v1/controls-shortcut.py'), '--serial', serial], check=True, timeout=150)
                assert adb('shell', 'pidof', app + ':paravoid_recovery').strip() != owner
            if mode != 'cancel':
                tap('Retry update access'); await_status('Pending: p2')
            assert device.state()['pending']['version'] == 2
            server.shutdown(); server.server_close()
            tap('Restart app…'); tap('Stop and restart')
            device.healthy(2)
            assert device.state()['active']['version'] == 2
            assert 'generation=A;asset=payload-asset;java=payload-java-resource' in ui()
            print('PASS: signed p2 admission/confirmed offline activation succeed after ' + mode, serial, flush=True)
    finally:
        adb('shell', 'run-as', app, 'chmod', '700', store)
        if gate is not None and gate.poll() is None:
            gate.terminate(); gate.wait(timeout=10)
        adb('forward', '--remove', 'tcp:' + port)
