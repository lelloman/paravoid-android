#!/usr/bin/env python3
"""Logged-out fixed-shell acceptance; only an explicit disposable emulator.

Inputs are immutable output directories from the isolated Pezzottify integration.
Never uses account credentials, bypasses admission, or installs a replacement shell.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Handler, Server
spec = importlib.util.spec_from_file_location('record', ROOT / 'compatibility/complete-v1/device-check.py')
record = importlib.util.module_from_spec(spec)
spec.loader.exec_module(record)
APP = 'com.lelloman.pezzottify.android.paravoid'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--a', type=Path, required=True)
    parser.add_argument('--b', type=Path, required=True)
    parser.add_argument('--broken', type=Path)
    parser.add_argument('--repair', type=Path)
    parser.add_argument('--keys', type=Path, required=True)
    parser.add_argument('--port', type=int, default=19165)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('physical devices are not supported')
    if bool(args.broken) != bool(args.repair):
        parser.error('--broken and --repair must be supplied together')

    def adb(*parts, binary=False, check=True):
        result = subprocess.run(['adb', '-s', args.serial, *map(str, parts)],
                                capture_output=True, text=not binary, timeout=120)
        if check and result.returncode:
            raise AssertionError(f'{parts}: {result.stdout!r} {result.stderr!r}')
        return result.stdout

    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
    assert adb('emu', 'avd', 'name').splitlines()[0] == args.avd
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
    # Refuse existing installations: an operator must deliberately choose a fresh
    # disposable emulator or explicitly remove their previous test installation.
    assert not adb('shell', 'pm', 'path', APP, check=False).strip(), 'Existing app data: refusing overwrite'
    with zipfile.ZipFile(args.a / 'shell.apk') as apk:
        policy = json.loads(apk.read('assets/paravoid/shell-policy.json'))
    distribution = policy['descriptor']['distribution']
    assert distribution['authentication'] == 'public'
    assert distribution['bootstrap'] == 'embedded'
    catalog = Catalog()
    server = Server(('127.0.0.1', args.port), catalog)
    requests = []
    class Observed(Handler):
        def do_GET(self):
            requests.append(self.path.split('?')[0])
            super().do_GET()
    server.RequestHandlerClass = Observed
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    key = serialization.load_der_private_key((args.keys / 'head.der').read_bytes(), None)
    revision = 0

    def offer(output, incompatible=False):
        nonlocal revision
        revision += 1
        envelope = (output / 'release.json').read_bytes()
        release = json.loads(base64.b64decode(json.loads(envelope)['body']))
        archive = output / 'payload.vpk'
        assert (release['shellContractId'] != policy['contractId']) == incompatible
        now = int(time.time())
        body = json.dumps(dict(version=1, applicationId=APP, shellContractId=policy['contractId'],
            channel='stable', sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1,
            headRevision=revision, issuedAt=now, expiresAt=now + 3600, status='available',
            release=dict(releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
                manifestSha256=hashlib.sha256(envelope).hexdigest(),
                archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                archiveSize=archive.stat().st_size)), sort_keys=True, separators=(',', ':')).encode()
        signed = json.dumps(dict(keyId='head', body=base64.b64encode(body).decode(),
            signature=base64.b64encode(key.sign(b'paravoid/v1/head\n' + body,
                padding.PKCS1v15(), hashes.SHA256())).decode())).encode()
        updated = Catalog()
        updated.add_head(APP, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk),
            abis=','.join(abis), runtime='1', format='1', protocol='1'), signed)
        updated.add_archive(APP, release['releaseId'], archive)
        server.catalog = updated
        return release['payloadVersion']

    def state():
        return record.Record(adb('exec-out', 'run-as', APP, 'cat',
                                 'no_backup/paravoid-v1/selection', binary=True)).selection()

    def nodes():
        adb('shell', 'uiautomator', 'dump', '/sdcard/paravoid-realapp.xml')
        return list(ET.fromstring(adb('shell', 'cat', '/sdcard/paravoid-realapp.xml')).iter('node'))

    def point(node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
        return str((x1 + x2) // 2), str((y1 + y2) // 2)

    def tap(label):
        node = next(n for n in nodes() if n.get('text', '').lower() == label.lower())
        adb('shell', 'input', 'tap', *point(node))

    def wait(predicate, description):
        for _ in range(60):
            if predicate():
                return
            time.sleep(1)
        raise AssertionError(description)

    def login():
        wait(lambda: {'Login', 'Sign in with SSO'} <= {n.get('text') for n in nodes()}, 'Login UI missing')

    def generation_marker(name):
        pid = adb('shell', 'pidof', APP).strip()
        logs = adb('logcat', '-d', '--pid=' + pid, '-s', 'ParavoidAcceptance:I', '*:S')
        assert re.search(r'ParavoidAcceptance: ' + re.escape(name) + r'\s*$', logs, re.MULTILINE), logs

    retained_host = 'http://127.0.0.1:1'

    def retained_preference():
        xml = adb('exec-out', 'run-as', APP, 'cat', 'shared_prefs/ConfigStore.xml', check=False)
        if not xml.startswith('<?xml'):
            return False
        return any(n.get('name') == 'HostUrl' and n.text == retained_host for n in ET.fromstring(xml))

    def seed_preference():
        field = next(n for n in nodes() if n.get('class') == 'android.widget.EditText')
        adb('shell', 'input', 'tap', *point(field))
        adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
        if field.get('text'):
            adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * len(field.get('text'))))
        adb('shell', 'input', 'text', retained_host)
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        # Login's normal setHost path commits ConfigStore before attempting empty
        # credentials. The loopback port cannot contact any production backend.
        tap('Login')
        wait(retained_preference, 'App-owned host preference not committed')

    def controls():
        adb('shell', 'am', 'start', '-W', '-n',
            APP + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher')
        wait(lambda: 'check now' in {n.get('text', '').lower() for n in nodes()}, 'Controls missing')

    def restart():
        tap('Restart app…')
        tap('Stop and restart')

    def databases():
        adb('shell', 'am', 'force-stop', APP)
        names = adb('shell', 'run-as', APP, 'ls', 'databases').splitlines()
        identities = {}
        with tempfile.TemporaryDirectory(prefix='pezzottify-realapp-db-') as directory:
            for name in names:
                assert '/' not in name and name not in ('.', '..')
                (Path(directory) / name).write_bytes(adb('exec-out', 'run-as', APP, 'cat', 'databases/' + name, binary=True))
            for name in ('StaticsDb', 'user_content'):
                with sqlite3.connect(str(Path(directory) / name)) as connection:
                    assert connection.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                    identities[name] = connection.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()
                    assert identities[name]
        return identities

    def incompatible():
        # A publisher-signed but mismatching contract must fail real admission.
        # This is a signed negative vector, not an APK manifest-change build.
        with tempfile.TemporaryDirectory(prefix='pezzottify-incompatible-') as directory:
            output = Path(directory)
            with zipfile.ZipFile(args.b / 'payload.vpk') as original:
                release = json.loads(base64.b64decode(json.loads(original.read('release.json'))['body']))
                release.update(releaseId='wrong-contract', payloadVersion=3, shellContractId='0' * 64)
                body = json.dumps(release, sort_keys=True, separators=(',', ':')).encode()
                release_key = serialization.load_der_private_key((args.keys / 'release.der').read_bytes(), None)
                envelope = json.dumps(dict(keyId='release', body=base64.b64encode(body).decode(),
                    signature=base64.b64encode(release_key.sign(b'paravoid/v1/release\n' + body,
                        padding.PKCS1v15(), hashes.SHA256())).decode()),
                    sort_keys=True, separators=(',', ':')).encode()
                (output / 'release.json').write_bytes(envelope)
                with zipfile.ZipFile(output / 'payload.vpk', 'w', compression=zipfile.ZIP_STORED) as target:
                    for entry in original.infolist():
                        if entry.filename == 'release.json':
                            target.writestr(entry.filename, envelope)
                        else:
                            with original.open(entry) as source, target.open(entry.filename, 'w') as destination:
                                shutil.copyfileobj(source, destination, 65536)
            before = state()
            offer(output, incompatible=True)
            controls()
            tap('Check now')
            wait(lambda: any('Update status: INCOMPATIBLE' in n.get('text', '') for n in nodes()),
                 'Mismatching signed release not rejected')
            assert state() == before, 'Incompatible release changed selected generation'
            print('PASS signed wrong-contract VPK rejected; active/healthy/pending journal unchanged', flush=True)

    try:
        offer(args.a)
        adb('reverse', f'tcp:{args.port}', f'tcp:{args.port}')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        assert 'Success' in adb('install', args.a / 'shell.apk')
        installed = adb('shell', 'pm', 'path', APP).strip()
        assert installed.startswith('package:') and '\n' not in installed
        installed_hash = adb('shell', 'sha256sum', installed.removeprefix('package:')).split()[0]
        assert installed_hash == hashlib.sha256((args.a / 'shell.apk').read_bytes()).hexdigest()
        adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
        login()
        generation_marker('A')
        wait(lambda: state()['healthy'] is not None, 'A not healthy')
        seed_preference()
        original = databases()
        adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
        login()
        print('PASS final-runtime real app embedded A: logged-out Compose/Hilt/Room startup', flush=True)
        for output, broken in [(args.b, False)] + ([(args.broken, True), (args.repair, False)] if args.broken else []):
            version = offer(output)
            controls()
            tap('Check now')
            wait(lambda: state()['pending'] is not None and state()['pending']['version'] == version, 'Update not staged')
            assert adb('shell', 'pm', 'path', APP).strip() == installed, 'Shell installation changed'
            restart()
            if broken:
                wait(lambda: state()['quarantined'], 'Broken update not quarantined')
                print(f'PASS payload {version}: startup fault quarantined', flush=True)
            else:
                login()
                generation_marker(output.name)
                wait(lambda: state()['healthy'] is not None and state()['healthy']['version'] == version, 'New payload not healthy')
                assert databases() == original
                assert retained_preference(), 'Application-owned ConfigStore preference lost'
                adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
                login()
                print(f'PASS payload {version}: fixed-shell download/activation and Room identity/integrity retained', flush=True)
                if output == args.b:
                    incompatible()
        assert any('/payloads/' in path or '/releases/' in path for path in requests), requests
        server.shutdown()
        adb('reverse', '--remove', f'tcp:{args.port}')
        assert databases() == original
        assert retained_preference()
        adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
        login()
        generation_marker('repair' if args.repair else 'B')
        assert state()['healthy']['version'] == (4 if args.repair else 2)
        assert adb('shell', 'sha256sum', installed.removeprefix('package:')).split()[0] == installed_hash
        print('PASS offline final cold relaunch; UI-created preference, Room integrity/identity and shell SHA-256 retained', flush=True)
        print('PASS logged-out scope only; no account, playback, browser login, or JNI execution claim', flush=True)
    finally:
        server.shutdown()
        server.server_close()
        adb('reverse', '--remove', f'tcp:{args.port}', check=False)


if __name__ == '__main__':
    main()
