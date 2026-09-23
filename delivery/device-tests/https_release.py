#!/usr/bin/env python3
"""Non-debuggable release APK + real personalization CLI + validated TLS, on an explicit emulator."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import ssl
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
sys.path.insert(0, str(ROOT / 'delivery/reference'))
from server import Catalog, Server, Handler

APP = 'com.lelloman.paravoidcompat.complete.paravoid'
NORMAL = 'com.lelloman.paravoidcompat.complete'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--server-port', type=int, default=18765)
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), 'Disposable emulator required'
assert 1 <= args.server_port <= 65535


def adb(*parts, check=True):
    result = subprocess.run(['adb', '-s', args.serial, *map(str, parts)], capture_output=True, text=True, timeout=60)
    assert not check or result.returncode == 0, result.stdout + result.stderr
    return result.stdout + result.stderr


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/paravoid-https.xml')
    return adb('shell', 'cat', '/sdcard/paravoid-https.xml')


def expect(text):
    for _ in range(20):
        state = ui()
        if text in state:
            return state
        time.sleep(.3)
    raise AssertionError('Missing UI state: ' + text + '\n' + state)


def point(node):
    a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return str((a + c) // 2), str((b + d) // 2)


def tap(label):
    node = next(n for n in ET.fromstring(ui()).iter('node') if n.attrib.get('text', '').lower() == label.lower())
    adb('shell', 'input', 'tap', *point(node))


def launch():
    adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')


def controls():
    w, h = map(int, re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size')).groups())
    for index in range(4):
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME'); time.sleep(.75)
        adb('shell', 'input', 'swipe', w // 2, h * 9 // 10, w // 2, h // 4, '400'); time.sleep(1)
        icons = [n for n in ET.fromstring(ui()).iter('node') if n.attrib.get('text') == 'Paravoid complete fixture']
        if not icons:
            continue
        x, y = point(icons[index % len(icons)])
        adb('shell', 'input', 'swipe', x, y, x, y, '900'); time.sleep(.5)
        nodes = list(ET.fromstring(ui()).iter('node'))
        shortcuts = [n for n in nodes if n.attrib.get('text') in ('App updates', 'Paravoid app updates')]
        if shortcuts:
            adb('shell', 'input', 'tap', *point(shortcuts[0])); expect('A local app generation is available.'); return
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    raise AssertionError('Release shell controls shortcut not found')


assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
fixture = ROOT / 'compatibility/complete-v1'
output = fixture / 'build/outputs/paravoid/paravoidAndroidRelease'
source, archive = output / 'shell.apk', output / 'payload.vpk'
with zipfile.ZipFile(source) as apk:
    policy = json.loads(apk.read('assets/paravoid/shell-policy.json'))
distribution = policy['descriptor']['distribution']
assert distribution['authentication'] == 'apkKey' and distribution['bootstrap'] == 'empty'
endpoint = 'https://127.0.0.1:18765/'
envelope = (output / 'release.json').read_bytes()
release = json.loads(base64.b64decode(json.loads(envelope)['body']))
assert release['shellContractId'] == policy['contractId']
sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
assert sdk in (30, 36)
now = int(time.time())


def sign(role, body):
    key = serialization.load_der_private_key((fixture / ('build/keys/' + role + '.der')).read_bytes(), None)
    encoded = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
    signature = key.sign(('paravoid/v1/' + role + '\n').encode() + encoded, padding.PKCS1v15(), hashes.SHA256())
    return json.dumps(dict(keyId=role, body=base64.b64encode(encoded).decode(), signature=base64.b64encode(signature).decode())).encode()


tokens = [base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=') for _ in range(2)]
catalog = Catalog('apkKey')
catalog.add_archive(APP, release['releaseId'], archive)
catalog.add_head(APP, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk), abis=','.join(abis),
    runtime='1', format='1', protocol='1'), sign('head', dict(version=1, applicationId=APP,
    shellContractId=policy['contractId'], channel='stable', sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1,
    headRevision=1, issuedAt=now, expiresAt=now + 3600, status='available', release=dict(
        releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
        manifestSha256=hashlib.sha256(envelope).hexdigest(), archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
        archiveSize=archive.stat().st_size))))
for token in tokens:
    catalog.grant(token, APP, ['stable'], [release['releaseId']])
requests, ranges = [], []  # Only fixture indices and byte ranges, never raw credentials.
truncate_once = threading.Event(); truncate_once.set()


class ObservedHandler(Handler):
    def do_GET(self):
        credential = next((i for i, token in enumerate(tokens) if self.headers.get('Authorization') == 'Bearer ' + token), -1)
        requests.append(credential)
        if '/releases/' in self.path:
            ranges.append(self.headers.get('Range'))
            if truncate_once.is_set() and credential >= 0:
                truncate_once.clear()
                entry = catalog.archives[(APP, release['releaseId'])]
                self.headers_for(200, 'application/vnd.paravoid.vpk', entry.size, entry.etag)
                self.send_header('Accept-Ranges', 'bytes'); self.end_headers()
                with archive.open('rb') as stream:
                    self.wfile.write(stream.read(65536)); self.wfile.flush()
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        super().do_GET()


def start_server(name):
    server = Server(('127.0.0.1', args.server_port), catalog)
    server.RequestHandlerClass = ObservedHandler
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(fixture / ('build/keys/' + name + '.pem'), fixture / ('build/keys/' + name + '-key.pem'))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


server = None
try:
    adb('reverse', 'tcp:18765', 'tcp:' + str(args.server_port))
    adb('uninstall', NORMAL, check=False); adb('uninstall', APP, check=False)
    adb('install', fixture / 'build/outputs/apk/normal/release/complete-v1-normal-release.apk')
    adb('shell', 'am', 'start', '-W', '-n', NORMAL + '/.MainActivity')
    expect('generation=A;asset=payload-asset;java=payload-java-resource')
    with tempfile.TemporaryDirectory(prefix='paravoid-https-release-') as tmp:
        carriers = []
        for index in range(2):
            grant = Path(tmp) / ('grant' + str(index) + '.json')
            grant.write_bytes(sign('grant', dict(version=1, applicationId=APP, shellContractId=policy['contractId'],
                audience=endpoint, grantId='https-' + str(index), keyId='https-' + str(index), key=tokens[index],
                issuedAt=now, expiresAt=now + 3600)))
            grant.chmod(0o600)
            carrier = Path(tmp) / ('carrier' + str(index) + '.apk')
            subprocess.run(['bash', str(ROOT / 'delivery/tools/personalize.sh'), str(source), str(carrier),
                '--grant', str(grant), '--apksigner', str(Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner')],
                check=True, timeout=120)
            carriers.append(carrier)
        server = start_server('untrusted')
        adb('install', source); launch(); expect('Update access unavailable')
        assert not requests
        assert 'not debuggable' in adb('shell', 'run-as', APP, 'id', check=False).lower()
        adb('install', '-r', carriers[0]); launch(); expect('Update: WAITING_TO_RETRY')
        assert not requests, 'Untrusted TLS reached HTTP handler'
        tap('Cancel download'); expect('Update: CANCELLED')
        server.shutdown(); server.server_close(); server = start_server('server')
        tap('Retry update access'); expect('Pending: ' + release['releaseId'])
        assert any(value and value.startswith('bytes=') for value in ranges), 'Interrupted TLS download did not resume'
        assert requests and all(value == 0 for value in requests)
        tap('Restart app…'); tap('Stop and restart')
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        print('PASS non-debuggable signed release: missing grant denied, untrusted TLS refused, personalized CLI carrier downloads/resumes/activates over trusted HTTPS', args.serial, flush=True)
        controls(); catalog.revoke(tokens[0]); tap('Check now'); expect('Update access unavailable')
        count = len(requests)
        adb('shell', 'am', 'force-stop', APP); launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        # A new controller's snapshot is IDLE when the foreground check is
        # throttled; the prior process's error text is not persisted.
        controls(); expect('Current: p1'); assert len(requests) == count
        adb('install', '-r', carriers[1]); launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        controls(); tap('Check now'); expect('Update: READY')
        assert len(requests) > count and all(value == 1 for value in requests[count:])
        print('PASS revoked key preserves active app/suppresses requests; APK credential replacement restores HTTPS updates with only the new key', args.serial, flush=True)
        count = len(requests)
        adb('install', '-r', source); launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        controls(); tap('Check now'); expect('Update access unavailable'); assert len(requests) == count
        server.shutdown(); server.server_close(); server = None
        adb('shell', 'am', 'force-stop', APP); launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        print('PASS stripping grant fails closed without anonymous fallback; retained release runs offline', args.serial, flush=True)
finally:
    if server is not None:
        server.shutdown(); server.server_close()
    adb('reverse', '--remove', 'tcp:18765')
