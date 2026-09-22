#!/usr/bin/env python3
"""Installed production APK-key tests, using disposable debug-HTTP test carriers.
The production personalization CLI deliberately rejects HTTP; these fixtures use
its signature-preserving insertion primitive, then verify developer signatures.
Runtime performs all real policy/grant/head/VPK verification on the device.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'delivery/reference'), str(ROOT / 'delivery/tools')]
from server import Catalog, Server, Handler
from apk_personalize import insert, developer_signatures
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
    def launch():
        adb('shell', 'am', 'start', '-W', '-n', LAUNCHER)
    def ui():
        adb('shell', 'uiautomator', 'dump', '/sdcard/delivery-ui.xml')
        return adb('shell', 'cat', '/sdcard/delivery-ui.xml')
    def expect(text):
        last = ''
        for _ in range(15):
            last = ui()
            if text in last:
                return last
            time.sleep(.5)
        raise AssertionError('Missing UI state: ' + text + '\n' + last)
    def tap(label):
        node = next(n for n in ET.fromstring(ui()).iter('node') if n.attrib.get('text', '').lower() == label.lower())
        a,b,c,d = map(int, re.findall(r'\d+', node.attrib['bounds']))
        adb('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))
    fixture = ROOT / 'compatibility/complete-v1'
    output = fixture / 'build/outputs/paravoid/paravoidAndroidDebug'
    source = output / 'shell.apk'
    with zipfile.ZipFile(source) as z:
        policy = json.loads(z.read('assets/paravoid/shell-policy.json'))
    assert policy['descriptor']['distribution']['authentication'] == 'apkKey'
    assert policy['descriptor']['distribution']['bootstrap'] == 'empty'
    now = int(time.time())
    def sign(role, body):
        key = serialization.load_der_private_key((fixture / ('build/keys/' + role + '.der')).read_bytes(), None)
        body = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
        signature = key.sign(('paravoid/v1/' + role + '\n').encode() + body, padding.PKCS1v15(), hashes.SHA256())
        return json.dumps(dict(keyId=role, body=base64.b64encode(body).decode(), signature=base64.b64encode(signature).decode())).encode()
    tokens = [base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=') for _ in range(2)]
    def grant(index, expired=False):
        return sign('grant', dict(version=1, applicationId=APP, shellContractId=policy['contractId'],
            audience='http://127.0.0.1:18765/', grantId='device-' + str(index), keyId='device-' + str(index),
            key=tokens[index], issuedAt=now-1000, expiresAt=now-10 if expired else now+3600))
    archive = output / 'payload.vpk'
    envelope = (output / 'release.json').read_bytes()
    release = json.loads(base64.b64decode(json.loads(envelope)['body']))
    assert release['shellContractId'] == policy['contractId']
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    catalog = Catalog('apkKey')
    catalog.add_head(APP, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk), abis=','.join(abis),
        runtime='1', format='1', protocol='1'), sign('head', dict(version=1, applicationId=APP,
        shellContractId=policy['contractId'], channel='stable', sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1,
        headRevision=1, issuedAt=now, expiresAt=now+3600, status='available', release=dict(
        releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
        manifestSha256=hashlib.sha256(envelope).hexdigest(), archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
        archiveSize=archive.stat().st_size))))
    catalog.add_archive(APP, release['releaseId'], archive)
    requests = []  # Booleans only: never retain or print request credentials.
    old_download = threading.Event()
    release_old = threading.Event()
    class ObservedHandler(Handler):
        def do_GET(self):
            requests.append(self.headers.get('Authorization') is not None)
            if '/releases/' in self.path and self.headers.get('Authorization') == 'Bearer ' + tokens[0]:
                old_download.set()
                release_old.wait(30)
            super().do_GET()
    server = Server(('127.0.0.1', 18765), catalog)
    server.RequestHandlerClass = ObservedHandler
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sdkroot = Path(os.environ.get('ANDROID_HOME', '/home/lelloman/Android/Sdk'))
    signer = sdkroot / 'build-tools/36.0.0/apksigner'
    original_signatures = developer_signatures(signer, source)
    try:
        adb('reverse', 'tcp:18765', 'tcp:18765')
        with tempfile.TemporaryDirectory(prefix='paravoid-device-grants-') as tmp:
            def carrier(name, payload):
                path = Path(tmp) / (name + '.apk')
                path.write_bytes(insert(source.read_bytes(), payload))
                path.chmod(0o600)
                assert developer_signatures(signer, path) == original_signatures
                return path
            bad = json.loads(grant(0)); bad['signature'] = base64.b64encode(bytes(384)).decode()
            invalid = carrier('invalid', json.dumps(bad).encode())
            expired = carrier('expired', grant(0, True))
            first = carrier('first', grant(0))
            replacement = carrier('replacement', grant(1))
            for name, apk in [('missing', source), ('invalid', invalid), ('expired', expired)]:
                adb('install', '-r', str(apk)); adb('shell', 'pm', 'clear', APP); launch()
                expect('Update access unavailable')
                tap('Retry update access'); expect('Update access unavailable')
                assert not requests, 'unusable grant must never issue HTTP or fall back to public'
                print('PASS:', name, 'grant denies HTTP and explicit retry')
            adb('install', '-r', str(first)); adb('shell', 'pm', 'clear', APP); launch()
            expect('Update access unavailable')
            assert len(requests) == 1 and all(requests)
            adb('shell', 'am', 'force-stop', APP); launch(); expect('Update access unavailable')
            assert len(requests) == 1, '403 suppression must survive process restart'
            print('PASS: authenticated 403 persists across restart without anonymous fallback')
            catalog.grant(tokens[0], APP, ['stable'], [release['releaseId']])
            tap('Retry update access')
            assert old_download.wait(10), 'explicit retry did not begin archive request'
            catalog.revoke(tokens[0])
            catalog.grant(tokens[1], APP, ['stable'], [release['releaseId']])
            # Package replacement occurs while the old archive request is outstanding.
            adb('install', '-r', str(replacement)); release_old.set(); launch()
            expect('Pending: ' + release['releaseId'])
            assert all(requests)
            print('PASS: APK credential replacement during active request stages with new grant')
            catalog.revoke(tokens[1]); tap('Check now'); expect('Update access unavailable')
            count = len(requests)
            adb('shell', 'am', 'force-stop', APP); launch()
            for _ in range(15):
                try:
                    probe = adb('shell', 'run-as', APP, 'cat', 'shared_prefs/probe.xml')
                    if 'generation=A;asset=payload-asset;java=payload-java-resource' in probe:
                        break
                except subprocess.CalledProcessError:
                    pass
                time.sleep(.5)
            else:
                raise AssertionError('revocation prevented offline use of accepted payload')
            # Main/recovery suppression currently uses different runtime directories.
            print('PASS: server revocation preserves accepted payload execution')
            print('Requests after switching from recovery to main:', len(requests) - count)
            print('Device:', args.serial, 'API', sdk)
    finally:
        release_old.set(); server.shutdown(); server.server_close()
        adb('reverse', '--remove', 'tcp:18765')

if __name__ == '__main__':
    main()
