"""Installed p1 -> p2 update, then explicit relaunch of the retained p1, the rollback hold and the return to p2."""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def run(root, serial, app, archive, catalog, query, head, server, adb, ui, tap):
    spec = importlib.util.spec_from_file_location('relaunch_device', root / 'compatibility/complete-v1/device-check.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    device = module.Device(serial, adb('emu', 'avd', 'name').splitlines()[0])
    from engine_state import controls

    def sign(role, body):
        key = serialization.load_der_private_key((root / ('compatibility/complete-v1/build/keys/' + role + '.der')).read_bytes(), None)
        body = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
        signature = key.sign(('paravoid/v1/' + role + '\n').encode() + body, padding.PKCS1v15(), hashes.SHA256())
        return json.dumps(dict(keyId=role, body=base64.b64encode(body).decode(), signature=base64.b64encode(signature).decode())).encode()

    def await_text(text):
        device.await_(lambda: text in ui(), text)

    def active(version):
        device.await_(lambda: device.state()['active'] is not None and device.state()['active']['version'] == version
                      and device.state()['pending'] is None, 'active generation ' + str(version))
        device.healthy(version)

    assert device.state()['active']['version'] == 1
    with tempfile.TemporaryDirectory(prefix='paravoid-relaunch-') as tmp:
        # p2: the real Android components under a new signed release identity, as publication_io does.
        candidate = Path(tmp) / 'p2.vpk'
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

        tap('Check now'); await_text('Download update')
        tap('Download update'); await_text('Ready to restart')
        tap('Restart app…'); tap('Stop and restart')
        active(2)
        print('PASS: signed p2 delivered and activated; p1 retained', serial, flush=True)

        # The retained p1 is listed and can be relaunched after a warning.
        controls(adb, app)
        await_text('Previous versions')
        screen = ui()
        assert 'Version 1' in screen and 'Last ran successfully' in screen, screen
        main = adb('shell', 'pidof', app).strip()
        tap('Relaunch this version…')
        warning = ui()
        assert 'older than the one you are using' in warning and 'version 2 or older' in warning, warning
        tap('Cancel')
        assert device.state()['active']['version'] == 2 and device.state()['pending'] is None
        tap('Relaunch this version…'); tap('Relaunch')
        active(1)
        assert adb('shell', 'pidof', app).strip() != main, 'The relaunch must go through a coordinated restart'
        assert 'generation=A;asset=payload-asset;java=payload-java-resource' in ui()
        print('PASS: confirmed relaunch of retained p1 restarts the app into p1', serial, flush=True)

        # The hold: p2 is still offered by the server but not installed again.
        controls(adb, app)
        await_text('Automatic updates are paused up to version 2')
        tap('Check now')
        device.await_(lambda: 'Checking' not in ui(), 'check finished')
        screen = ui()
        assert 'is available to download' not in screen and 'Download update' not in screen, screen
        assert device.state()['active']['version'] == 1 and device.state()['pending'] is None
        print('PASS: rollback hold keeps the offered p2 from being downloaded again', serial, flush=True)

        # Return to p2 the same way; that ends the hold.
        tap('Return to this version…'); tap('Relaunch')
        active(2)
        controls(adb, app)
        await_text('Previous versions')
        assert 'Automatic updates are paused' not in ui()
        print('PASS: return to the newer p2 via relaunch ends the hold', serial, flush=True)
