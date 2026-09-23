#!/usr/bin/env python3
"""Exercise a built production empty shell against the reference server on an explicit emulator.
Build normal and empty shell APKs plus packageParavoidAndroidDebugParavoidVpk first.
Only the disposable emulator's fixture app is cleared; no other device is selected.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import select
import socket
import subprocess
import sys
import threading
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Server, Handler
APP = 'com.lelloman.paravoidcompat.complete.paravoid'
LAUNCHER = APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--server-port', type=int, default=18765)
    parser.add_argument('--network-transitions', action='store_true',
                        help='Exercise actual Android Wi-Fi metering changes and explicit override')
    parser.add_argument('--restart-controls', action='store_true',
                        help='Activate through confirmed shell controls, never adb force-stop')
    parser.add_argument('--storage-pressure', action='store_true',
                        help='Allocate a disposable app-private filler; test low-space rejection and 512 MiB admission')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('a dedicated disposable emulator serial is required')
    if args.network_transitions and args.storage_pressure:
        parser.error('network and storage pressure are separate runs')
    if not 1 <= args.server_port <= 65535:
        parser.error('invalid host port')
    def adb(*parts, timeout=45):
        return subprocess.check_output(['adb', '-s', args.serial, *parts], text=True, stderr=subprocess.STDOUT, timeout=timeout)
    def set_metered(state):
        # These images apply the override but return 255; require state readback,
        # not just shell exit status. The CLI spells its default value 'undefined'.
        result = subprocess.run(['adb', '-s', args.serial, 'shell', 'cmd', 'netpolicy', 'set',
                                 'metered-network', 'AndroidWifi', 'undefined' if state == 'none' else state],
                                text=True, capture_output=True, timeout=45)
        assert result.returncode in (0, 255), result.stdout + result.stderr
        for _ in range(20):
            rows = adb('shell', 'cmd', 'netpolicy', 'list', 'wifi-networks').splitlines()
            if {row.rsplit(';', 1)[1] for row in rows if row.startswith('AndroidWifi;')} == {state}:
                time.sleep(.5)
                return
            time.sleep(.1)
        raise AssertionError('Metering override did not apply: ' + state)
    filler = 'files/paravoid-storage-test-filler'
    def free_bytes():
        return int(adb('shell', 'run-as', APP, 'df', '-k', '.').splitlines()[-1].split()[3]) * 1024
    def ui():
        adb('shell', 'uiautomator', 'dump', '/sdcard/delivery-ui.xml')
        return adb('shell', 'cat', '/sdcard/delivery-ui.xml')
    def tap(label):
        node = next(n for n in ET.fromstring(ui()).iter('node')
                    if n.attrib.get('text', '').lower() == label.lower())
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    fixture = ROOT / 'compatibility/complete-v1'
    output = fixture / 'build/outputs/paravoid/paravoidAndroidDebug'
    with zipfile.ZipFile(output / 'shell.apk') as apk:
        policy = json.loads(apk.read('assets/paravoid/shell-policy.json'))
    normal = fixture / 'build/outputs/apk/normal/debug/complete-v1-normal-debug.apk'
    normal_app = 'com.lelloman.paravoidcompat.complete'
    adb('install', '-r', str(normal))
    adb('shell', 'am', 'force-stop', normal_app)
    adb('shell', 'am', 'start', '-W', '-n', normal_app + '/.MainActivity')
    assert 'generation=A;asset=payload-asset;java=payload-java-resource' in adb(
        'shell', 'run-as', normal_app, 'cat', 'shared_prefs/probe.xml')
    print('PASS: normal packaging launches Application, Activity, assets and Java resources')
    distribution = policy['descriptor']['distribution']
    assert distribution['bootstrap'] == 'empty' and distribution['authentication'] == 'public'
    archive = output / 'payload.vpk'
    envelope = (output / 'release.json').read_bytes()
    release = json.loads(base64.b64decode(json.loads(envelope)['body']))
    assert release['shellContractId'] == policy['contractId'], 'Build a matching empty-policy VPK'
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    assert sdk in (30, 36), sdk
    now = int(time.time())
    head = dict(version=1, applicationId=APP, shellContractId=policy['contractId'], channel='stable',
                sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1, headRevision=1,
                issuedAt=now, expiresAt=now + 3600, status='available',
                release=dict(releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
                             manifestSha256=hashlib.sha256(envelope).hexdigest(),
                             archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(), archiveSize=archive.stat().st_size))
    body = json.dumps(head, sort_keys=True, separators=(',', ':')).encode()
    key = serialization.load_der_private_key((fixture / 'build/keys/head.der').read_bytes(), None)
    signed = json.dumps(dict(keyId='head', body=base64.b64encode(body).decode(),
                            signature=base64.b64encode(key.sign(b'paravoid/v1/head\n' + body, padding.PKCS1v15(), hashes.SHA256())).decode())).encode()
    catalog = Catalog()
    query = dict(contract=policy['contractId'], channel='stable', sdk=str(sdk), abis=','.join(abis),
                 runtime='1', format='1', protocol='1')
    catalog.add_head(APP, query, signed)
    catalog.add_archive(APP, release['releaseId'], archive)
    heads, archives = [], []
    held, disconnected, release_transfer = threading.Event(), threading.Event(), threading.Event()
    class ObservedHandler(Handler):
        def do_GET(self):
            if '/head?' in self.path:
                heads.append(True)
            else:
                archives.append(True)
                if args.network_transitions and not release_transfer.is_set():
                    held.set()
                    deadline = time.monotonic() + 120
                    while not release_transfer.is_set() and time.monotonic() < deadline:
                        ready, _, _ = select.select([self.connection], [], [], .1)
                        if ready:
                            try:
                                eof = self.connection.recv(1, socket.MSG_PEEK) == b''
                            except ConnectionResetError:
                                eof = True
                            if eof:
                                disconnected.set(); return
            super().do_GET()
    server = Server(('127.0.0.1', args.server_port), catalog)
    server.RequestHandlerClass = ObservedHandler
    original_metering = None
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        print(adb('install', '-r', str(output / 'shell.apk')).strip())
        print(adb('shell', 'pm', 'clear', APP).strip())
        adb('reverse', 'tcp:18765', 'tcp:' + str(args.server_port))
        if args.network_transitions:
            wifi = [line.rsplit(';', 1) for line in adb('shell', 'cmd', 'netpolicy', 'list', 'wifi-networks').splitlines() if line]
            assert wifi and all(name == 'AndroidWifi' for name, _ in wifi), 'Requires disposable emulator AndroidWifi'
            assert len({state for _, state in wifi}) == 1
            original_metering = wifi[0][1]
            set_metered('true')
            with tempfile.TemporaryDirectory(prefix='paravoid-network-prefs-') as tmp:
                local = Path(tmp) / 'preferences'
                local.write_text('checks=true\ndownloads=true\nunmetered=true\nlastAutomaticCheck=0\n')
                remote = '/data/local/tmp/' + Path(tmp).name
                adb('push', str(local), remote)
                try:
                    adb('shell', 'run-as', APP, 'mkdir', '-p', 'no_backup')
                    adb('shell', 'run-as', APP, 'cp', remote, 'no_backup/paravoid-update-preferences')
                finally:
                    adb('shell', 'rm', '-f', remote)
        if args.storage_pressure:
            adb('shell', 'run-as', APP, 'mkdir', '-p', 'files')
            filler_mib = free_bytes() // (1024 * 1024) - 48
            assert filler_mib > 512, 'Need room for bounded storage-pressure fixture'
            # Real allocated blocks, never sparse growth. Only this named fixture file is removed.
            adb('shell', 'run-as', APP, 'dd', 'if=/dev/zero', 'of=' + filler,
                'bs=1048576', 'count=' + str(filler_mib), timeout=240)
            assert 0 < free_bytes() < 64 * 1024 * 1024
        print(adb('shell', 'am', 'start', '-W', '-n', LAUNCHER).strip())
        if args.network_transitions:
            for _ in range(30):
                if heads and 'Pending: none' in ui():
                    break
                time.sleep(.25)
            else:
                raise AssertionError('Metered automatic discovery did not run')
            time.sleep(2)
            assert not archives, 'Automatic download started on metered Wi-Fi'
            set_metered('false')
            adb('shell', 'am', 'force-stop', APP)
            adb('shell', 'am', 'start', '-W', '-n', LAUNCHER)
            assert held.wait(15), 'Unmetered automatic attempt did not start archive transfer'
            assert not disconnected.is_set()
            set_metered('true')
            assert disconnected.wait(10), 'Metering transition did not cancel automatic archive HTTP'
            assert 'Pending: none' in ui()
            release_transfer.set()
            tap('Check now')
            for _ in range(30):
                if 'Pending: ' + release['releaseId'] in ui():
                    break
                time.sleep(.25)
            else:
                raise AssertionError('Explicit check did not override unmetered-only restriction')
            adb('shell', 'am', 'force-stop', APP)
            adb('shell', 'am', 'start', '-W', '-n', LAUNCHER)
            assert 'generation=A;asset=payload-asset;java=payload-java-resource' in ui()
            print('PASS: metered head-only -> unmetered automatic archive -> metered cancellation -> explicit override and payload launch', args.serial)
            return
        if args.storage_pressure:
            for _ in range(30):
                last = ui()
                if 'INSUFFICIENT_STORAGE' in last:
                    break
                time.sleep(1)
            else:
                raise AssertionError('Missing low-space rejection: ' + last)
            assert 'Current: none' in last and 'Pending: none' in last
            print('PASS: actual app-private allocated pressure rejects update below 64 MiB')
            # Shrinking deallocates existing blocks; it does not fake free capacity with a sparse file.
            shrink = (512 * 1024 * 1024 - free_bytes() + 1048575) // 1048576
            adb('shell', 'run-as', APP, 'truncate', '-s', str((filler_mib - shrink) * 1048576), filler)
            assert 480 * 1048576 < free_bytes() < 600 * 1048576
            tap('Check now')
        last = ''
        for _ in range(30):
            adb('shell', 'uiautomator', 'dump', '/sdcard/delivery-ui.xml')
            last = adb('shell', 'cat', '/sdcard/delivery-ui.xml')
            if 'Pending: ' + release['releaseId'] in last and 'payload ' + str(release['payloadVersion']) in last:
                break
            time.sleep(1)
        else:
            print(last)
            raise AssertionError('Production bootstrap did not stage pending payload')
        assert 'Current: none' in last, 'download must not activate'
        for label in ('Check now', 'Retry update access', 'Cancel download', 'Automatically check for updates'):
            assert label.lower() in last.lower(), label
        print('PASS: production reference delivery stages pending; shell controls visible; no activation')
        if args.storage_pressure:
            assert free_bytes() < 600 * 1048576
            print('PASS: real signed VPK download and materialization with less than 600 MiB free')
        for label in ('Automatically check for updates', 'Automatically download updates',
                      'Automatic downloads only on unmetered networks'):
            tap(label)
        for _ in range(40):
            preferences = adb('shell', 'run-as', APP, 'cat', 'no_backup/paravoid-update-preferences')
            if 'checks=false' in preferences and 'downloads=false' in preferences and 'unmetered=true' in preferences:
                break
            time.sleep(.25)
        else:
            raise AssertionError('Updated preferences were not persisted')
        tap('1')
        tap('2')
        assert 'text="2"' in ui()
        tap('Check now')
        assert 'Update: READY' in ui(), 'explicit check must override automatic-download preference'
        tap('Retry update access')
        assert 'Update: READY' in ui()
        print('PASS: explicit check/retry, persisted automatic/unmetered preferences, retention control')
        server.shutdown()
        server.server_close()
        tap('Check now')
        tap('Cancel download')
        assert 'Update: CANCELLED' in ui()
        print('PASS: cancel stops an offline attempt')
        if args.restart_controls:
            old_main = adb('shell', 'pidof', APP).strip()
            recovery = adb('shell', 'pidof', APP + ':paravoid_recovery').strip()
            assert old_main and recovery, 'Exercise an existing unavailable main process, not only recovery'
            tap('Restart app…')
            assert 'Unsaved changes may be lost' in ui()
            tap('Cancel')
            assert adb('shell', 'pidof', APP).strip() == old_main
            assert 'Current: none' in ui(), 'Cancelling confirmation must not activate'
            tap('Restart app…')
            tap('Stop and restart')
        else:
            adb('shell', 'am', 'force-stop', APP)
            adb('shell', 'am', 'start', '-W', '-n', LAUNCHER)
        for _ in range(15):
            try:
                probe = adb('shell', 'run-as', APP, 'cat', 'shared_prefs/probe.xml')
                if 'generation=A;asset=payload-asset;java=payload-java-resource' in probe:
                    break
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        else:
            raise AssertionError('Offline cold start did not execute A')
        print('PASS: offline cold start executes downloaded production payload A')
        if args.restart_controls:
            assert adb('shell', 'pidof', APP).strip() != old_main
            assert adb('shell', 'pidof', APP + ':paravoid_recovery').strip() == recovery
            print('PASS: cancelled confirmation preserves main; confirmed restart replaces main, preserves recovery, activates offline without adb force-stop')
        print('Device:', args.serial, 'API', sdk, 'ABIs', ','.join(abis))
    finally:
        release_transfer.set()
        if original_metering is not None:
            set_metered(original_metering)
        if args.storage_pressure:
            adb('shell', 'run-as', APP, 'rm', '-f', filler)
        server.shutdown()
        server.server_close()
        adb('reverse', '--remove', 'tcp:18765')

if __name__ == '__main__':
    main()
