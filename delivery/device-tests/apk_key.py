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
import select
import socket
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
    parser.add_argument('--server-port', type=int, default=18765,
                        help='Host-side port; adb reverse keeps the APK endpoint at 18765')
    parser.add_argument('--retry-replacement', action='store_true',
                        help='Replace the installed credential while a one-hour retry is durably scheduled')
    parser.add_argument('--retry-crash', action='store_true',
                        help='SIGKILL app processes during all three retries; verify durable budget exhaustion')
    parser.add_argument('--staging-replacement-only', action='store_true',
                        help='Use JDWP to replace the APK after real VPK verification, before publication')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('a dedicated disposable emulator serial is required')
    if not 1 <= args.server_port <= 65535:
        parser.error('invalid host server port')
    if args.staging_replacement_only and (args.retry_crash or args.retry_replacement):
        parser.error('staging replacement is a separate run from retry tests')
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
    head_archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
    envelope = (output / 'release.json').read_bytes()
    release = json.loads(base64.b64decode(json.loads(envelope)['body']))
    assert release['shellContractId'] == policy['contractId']
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    root_crash = args.retry_crash and adb('shell', 'id', '-u').strip() == '0'
    if args.retry_crash and sdk == 30 and not root_crash:
        raise AssertionError('This API 30 crash gate requires adb -s SERIAL root before starting the suite')
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    catalog = Catalog('apkKey')
    catalog.add_head(APP, dict(contract=policy['contractId'], channel='stable', sdk=str(sdk), abis=','.join(abis),
        runtime='1', format='1', protocol='1'), sign('head', dict(version=1, applicationId=APP,
        shellContractId=policy['contractId'], channel='stable', sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1,
        headRevision=1, issuedAt=now, expiresAt=now+3600, status='available', release=dict(
        releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
        manifestSha256=hashlib.sha256(envelope).hexdigest(), archiveSha256=head_archive_hash,
        archiveSize=archive.stat().st_size))))
    catalog.add_archive(APP, release['releaseId'], archive)
    requests = []  # Booleans only: never retain or print request credentials.
    credential_ids = []  # Fixture indices, never bearer values.
    retry_head = threading.Event()
    retry_seconds = 3600
    crash_heads = threading.Event()
    release_crash = threading.Event()
    held_heads = []  # Disconnect events only, no request credentials.
    old_download = threading.Event()
    release_old = threading.Event()
    hold_main = threading.Event()
    main_download = threading.Event()
    main_disconnected = threading.Event()
    release_main = threading.Event()
    class ObservedHandler(Handler):
        def do_GET(self):
            requests.append(self.headers.get('Authorization') is not None)
            credential_ids.append(next((i for i, token in enumerate(tokens)
                                        if self.headers.get('Authorization') == 'Bearer ' + token), -1))
            if '/head?' in self.path and retry_head.is_set():
                retry_head.clear()
                self.send_response(429)
                self.send_header('Retry-After', str(retry_seconds))
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            if '/head?' in self.path and crash_heads.is_set():
                disconnected = threading.Event()
                held_heads.append(disconnected)
                deadline = time.monotonic() + 120
                while not release_crash.is_set() and time.monotonic() < deadline:
                    readable, _, _ = select.select([self.connection], [], [], .1)
                    if readable:
                        try:
                            eof = self.connection.recv(1, socket.MSG_PEEK) == b''
                        except ConnectionResetError:
                            eof = True
                        if eof:
                            disconnected.set()
                            return
                return
            if '/releases/' in self.path and self.headers.get('Authorization') == 'Bearer ' + tokens[0]:
                old_download.set()
                release_old.wait(30)
            if '/releases/' in self.path and hold_main.is_set():
                main_download.set()
                deadline = time.monotonic() + 120
                while not release_main.is_set() and time.monotonic() < deadline:
                    readable, _, _ = select.select([self.connection], [], [], .1)
                    if readable and self.connection.recv(1, socket.MSG_PEEK) == b'':
                        main_disconnected.set()
                        return
            super().do_GET()
    server = Server(('127.0.0.1', args.server_port), catalog)
    server.RequestHandlerClass = ObservedHandler
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sdkroot = Path(os.environ.get('ANDROID_HOME', '/home/lelloman/Android/Sdk'))
    signer = sdkroot / 'build-tools/36.0.0/apksigner'
    original_signatures = developer_signatures(signer, source)
    try:
        adb('reverse', 'tcp:18765', 'tcp:' + str(args.server_port))
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
            if args.staging_replacement_only:
                gate_dir = Path(tmp) / 'gate'
                gate_dir.mkdir()
                subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', str(gate_dir),
                                str(ROOT / 'delivery/device-tests/StagingGate.java')], check=True, timeout=45)
                catalog.grant(tokens[0], APP, ['stable'], [release['releaseId']])
                adb('install', '-r', str(first)); adb('shell', 'pm', 'clear', APP); launch()
                assert old_download.wait(15), 'Initial archive request not held'
                owner = adb('shell', 'pidof', APP + ':paravoid_recovery').strip()
                assert owner.isdigit()
                port = adb('forward', 'tcp:0', 'jdwp:' + owner).strip()
                assert port.isdigit()
                gate = None
                def marker(name):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        if (gate_dir / name).exists():
                            return
                        if gate.poll() is not None:
                            raise AssertionError('Debugger exited before ' + name)
                        time.sleep(.1)
                    raise AssertionError('Debugger did not reach ' + name)
                def private_bytes(path):
                    return subprocess.check_output(['adb', '-s', args.serial, 'exec-out', 'run-as', APP,
                                                    'cat', path], timeout=45)
                try:
                    with (gate_dir / 'log').open('w') as log:
                        gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', str(gate_dir),
                                                 'StagingGate', port, str(gate_dir)], stdout=log, stderr=log)
                        marker('ready'); release_old.set(); marker('verified')
                        store = 'no_backup/paravoid-v1/'
                        staged = adb('shell', 'run-as', APP, 'find', store + 'staging', '-name', 'archive.vpk').splitlines()
                        assert len(staged) == 1
                        assert hashlib.sha256(private_bytes(staged[0])).hexdigest() == head_archive_hash
                        assert not adb('shell', 'run-as', APP, 'ls', store + 'generations').strip()
                        security = private_bytes(store + 'security')
                        catalog.revoke(tokens[0]); catalog.grant(tokens[1], APP, ['stable'], [release['releaseId']])
                        count = len(requests)
                        adb('install', '-r', str(replacement))
                        marker('disconnected'); assert gate.wait(timeout=10) == 0
                        assert security == private_bytes(store + 'security'), 'Interrupted staging must not reset security history'
                        assert not adb('shell', 'run-as', APP, 'ls', store + 'generations').strip(), 'Old staging was published'
                        launch(); expect('Pending: ' + release['releaseId'])
                        assert credential_ids[count:] and all(i == 1 for i in credential_ids[count:])
                        assert not adb('shell', 'run-as', APP, 'ls', store + 'staging').strip()
                        adb('shell', 'am', 'force-stop', APP); launch()
                        expect('generation=A;asset=payload-asset;java=payload-java-resource')
                        print('PASS: verified private staging interrupted by APK replacement; no old publication, security preserved, new-grant retry and payload launch succeed')
                        print('Device:', args.serial, 'API', sdk)
                finally:
                    if gate is not None and gate.poll() is None:
                        gate.terminate(); gate.wait(timeout=10)
                    adb('forward', '--remove', 'tcp:' + port)
                return
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
            preference_path = 'no_backup/paravoid-update-preferences'
            remote_preferences = '/data/local/tmp/' + Path(tmp).name + '-preferences'
            def allow_automatic_check():
                # Test-only non-security scheduler state: make the six-hour throttle eligible.
                # Do not alter wall time, signed grants, denial markers or replay history.
                saved = adb('shell', 'run-as', APP, 'cat', preference_path)
                assert 'checks=true' in saved and 'downloads=true' in saved
                aged, matches = re.subn(r'(?m)^lastAutomaticCheck=\d+$', 'lastAutomaticCheck=1', saved)
                assert matches == 1
                local = Path(tmp) / 'preferences'
                local.write_text(aged)
                adb('push', str(local), remote_preferences)
                try:
                    adb('shell', 'run-as', APP, 'cp', remote_preferences, preference_path)
                finally:
                    adb('shell', 'rm', '-f', remote_preferences)
            adb('shell', 'am', 'force-stop', APP)
            allow_automatic_check()
            assert int(time.time()) < now + 3600, 'Grant expiry must not mask suppression'
            launch()
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
            print('PASS: server revocation preserves accepted payload execution')
            for _ in range(30):
                saved = adb('shell', 'run-as', APP, 'cat', preference_path)
                match = re.search(r'(?m)^lastAutomaticCheck=(\d+)$', saved)
                if match and int(match[1]) > 1:
                    break
                time.sleep(.2)
            else:
                raise AssertionError('Main never attempted an eligible automatic check')
            time.sleep(2)
            assert len(requests) == count, 'Recovery denial must suppress main, independent of throttle'
            print('PASS: recovery 403 suppresses fresh main with eligible throttle and unexpired grant')
            shortcut = [sys.executable, str(ROOT / 'integration-v1/controls-shortcut.py'), '--serial', args.serial]
            subprocess.run(shortcut, check=True, timeout=120)
            catalog.grant(tokens[1], APP, ['stable'], [release['releaseId']])
            tap('Retry update access'); expect('Update: READY')
            assert len(requests) > count, 'Explicit recovery retry must lift shared denial'
            # Start only main, with an eligible check. Its archive response remains blocked
            # until recovery cancels it; server observes socket EOF before any response.
            adb('shell', 'am', 'force-stop', APP)
            allow_automatic_check()
            hold_main.set(); launch()
            assert main_download.wait(15), 'Main did not start the held archive request'
            absent = subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', APP + ':paravoid_recovery'],
                                    text=True, capture_output=True, timeout=45)
            assert not absent.stdout.strip(), 'Download owner must be main, not recovery'
            main_pid = adb('shell', 'pidof', APP).strip()
            subprocess.run(shortcut, check=True, timeout=120)
            assert not main_disconnected.is_set(), 'Request ended before cancellation'
            tap('Cancel download')
            assert main_disconnected.wait(10), 'Recovery cancellation did not disconnect main HTTP'
            release_main.set()
            last = expect('Update: CANCELLED')
            assert 'Pending: none' in last, 'Pre-handoff cancellation must not stage'
            assert adb('shell', 'pidof', APP).strip() == main_pid
            assert all(requests)
            print('PASS: main HTTP cancelled by recovery; socket closed before response, no pending payload, main survives')
            if args.retry_crash:
                hold_main.clear(); retry_seconds = 5; crash_heads.set(); retry_head.set()
                tap('Retry update access')
                for index, consumed in enumerate((2, 3, 4)):
                    deadline = time.monotonic() + 20
                    while len(held_heads) <= index and time.monotonic() < deadline:
                        time.sleep(.1)
                    assert len(held_heads) == index + 1, 'Expected exactly one interrupted retry request'
                    saved_retry = adb('shell', 'run-as', APP, 'cat', preference_path + '.retry')
                    assert int(re.search(r'(?m)^retries=(\d+)$', saved_retry)[1]) == consumed
                    assert not held_heads[index].is_set(), 'Request ended before crash injection'
                    # Background Activities first so framework UI recovery cannot race
                    # the controlled relaunch. Kill only validated fixture-owned PIDs.
                    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                    killed = []
                    for process_name in (APP, APP + ':paravoid_recovery'):
                        result = subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', process_name],
                                                text=True, capture_output=True, timeout=45)
                        for pid in result.stdout.split():
                            assert pid.isdigit()
                            # API 30's toybox kill applet misparses numeric signals
                            # under run-as; use the shell builtin with a digits-only PID.
                            if root_crash:
                                adb('shell', 'sh', '-c', "'kill -9 " + pid + "'")
                            else:
                                adb('shell', 'run-as', APP, 'sh', '-c', "'kill -9 " + pid + "'")
                            killed.append(pid)
                    assert killed and held_heads[index].wait(10), 'Owner death did not close held HTTP'
                    assert saved_retry == adb('shell', 'run-as', APP, 'cat', preference_path + '.retry')
                    launch()
                count = len(requests)
                for _ in range(40):
                    pending_retry = subprocess.run(['adb', '-s', args.serial, 'shell', 'run-as', APP,
                                                    'test', '-e', preference_path + '.retry'], timeout=45)
                    if pending_retry.returncode == 1:
                        break
                    time.sleep(.25)
                else:
                    raise AssertionError('Exhausted retry record was not cleared after restart')
                expect('generation=A;asset=payload-asset;java=payload-java-resource')
                time.sleep(2)
                assert len(requests) == count and len(held_heads) == 3, 'Restart reset the retry budget'
                crash_heads.clear(); release_crash.set(); retry_seconds = 3600
                subprocess.run(shortcut, check=True, timeout=120)
                tap('Retry update access'); expect('Update: READY')
                assert len(requests) >= count + 2, 'Explicit new attempt must remain possible after exhaustion'
                print('PASS: SIGKILL during retries preserves counters 2/3/4; restart sends no fourth retry; active app and explicit retry work')
            if args.retry_replacement:
                hold_main.clear(); retry_head.set()
                tap('Retry update access'); expect('Update: WAITING_TO_RETRY')
                saved_retry = adb('shell', 'run-as', APP, 'cat', preference_path + '.retry')
                due = int(re.search(r'(?m)^due=(\d+)$', saved_retry)[1])
                assert due > int(time.time()) + 3000, 'Need a real long persisted Retry-After'
                count = len(requests)
                catalog.revoke(tokens[1]); catalog.grant(tokens[0], APP, ['stable'], [release['releaseId']])
                # Android kills the old app processes; the persisted retry survives the APK replacement.
                adb('install', '-r', str(first)); launch()
                for _ in range(60):
                    pending_retry = subprocess.run(['adb', '-s', args.serial, 'shell', 'run-as', APP,
                                                    'test', '-e', preference_path + '.retry'], timeout=45)
                    if len(requests) >= count + 2 and pending_retry.returncode == 1:
                        break
                    time.sleep(.25)
                else:
                    raise AssertionError('New installed credential did not discard the old persisted delay')
                assert credential_ids[count:] and all(i == 0 for i in credential_ids[count:])
                assert int(time.time()) < now + 3600, 'Grant expiry must not explain old retry rejection'
                assert 'generation=A;asset=payload-asset;java=payload-java-resource' in adb(
                    'shell', 'run-as', APP, 'cat', 'shared_prefs/probe.xml')
                print('PASS: APK credential replacement discards old one-hour retry; only replacement key requests, active payload survives')
            print('Device:', args.serial, 'API', sdk)
    finally:
        release_old.set(); release_main.set(); release_crash.set(); server.shutdown(); server.server_close()
        adb('reverse', '--remove', 'tcp:18765')

if __name__ == '__main__':
    main()
