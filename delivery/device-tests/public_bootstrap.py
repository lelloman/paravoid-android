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
import subprocess
import sys
import threading
import time
import zipfile
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Server
APP = 'com.lelloman.paravoidcompat.complete.paravoid'
LAUNCHER = APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('a dedicated disposable emulator serial is required')
    def adb(*parts):
        return subprocess.check_output(['adb', '-s', args.serial, *parts], text=True, stderr=subprocess.STDOUT, timeout=45)
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
    server = Server(('127.0.0.1', 18765), catalog)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        print(adb('install', '-r', str(output / 'shell.apk')).strip())
        print(adb('shell', 'pm', 'clear', APP).strip())
        adb('reverse', 'tcp:18765', 'tcp:18765')
        print(adb('shell', 'am', 'start', '-W', '-n', LAUNCHER).strip())
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
        def ui():
            adb('shell', 'uiautomator', 'dump', '/sdcard/delivery-ui.xml')
            return adb('shell', 'cat', '/sdcard/delivery-ui.xml')
        def tap(label):
            nodes = ET.fromstring(ui()).iter('node')
            node = next(n for n in nodes if n.attrib.get('text', '').lower() == label.lower())
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        for label in ('Automatically check for updates', 'Automatically download updates',
                      'Automatic downloads only on unmetered networks'):
            tap(label)
        preferences = adb('shell', 'run-as', APP, 'cat', 'no_backup/paravoid-update-preferences')
        assert 'checks=false' in preferences and 'downloads=false' in preferences and 'unmetered=true' in preferences
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
        print('Device:', args.serial, 'API', sdk, 'ABIs', ','.join(abis))
    finally:
        server.shutdown()
        server.server_close()
        adb('reverse', '--remove', 'tcp:18765')

if __name__ == '__main__':
    main()
