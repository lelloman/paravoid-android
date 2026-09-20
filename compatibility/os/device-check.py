#!/usr/bin/env python3
"""Production APK cross-UID sharing checks; use a dedicated unlocked emulator."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PEER = 'com.lelloman.paravoidcompat.os.peer'


def adb(*args, check=True):
    result = subprocess.run(['adb', '-s', os.environ['ANDROID_SERIAL'], *args],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stdout}')
    return result.stdout.strip()


def report(app):
    raw = adb('shell', 'run-as', app, 'cat', 'shared_prefs/os-probe.xml', check=False)
    try:
        return {node.get('name'): node.text if node.tag == 'string' else node.get('value')
                for node in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def check_mode(mode):
    shell = mode == 'paravoidAndroid'
    app = 'com.lelloman.paravoidcompat.os.' + ('paravoid' if shell else 'normal')
    apk = ROOT / f'build/outputs/apk/{mode}/debug/os-compatibility-{mode}-debug.apk'
    adb('install', '-r', str(apk))
    adb('shell', 'pm', 'clear', app)
    adb('shell', 'pm', 'clear', PEER)
    token = str(uuid.uuid4())
    launcher = ('com.lelloman.paravoidandroid.runtime.LauncherActivity' if shell
                else 'com.lelloman.paravoidcompat.os.ProbeActivity')
    try:
        # Short-lived launchers/peers can finish before API 28 reports a drawn
        # Activity to `am start -W`. Wait for the run-token report instead.
        adb('shell', 'am', 'start', '-n', app + '/' + launcher, '--es', 'probeRun', token)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            snapshot = report(app)
            if snapshot.get('run') == token:
                results = json.loads(snapshot['results'])
                for name, value in results.items():
                    print(f'{mode} {name}: {value}', flush=True)
                assert len(results) == 16 and all(value == 'PASS' for value in results.values()), results
                break
            time.sleep(0.3)
        else:
            raise AssertionError(f'Timed out waiting for {mode}: {report(app)}')

        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        old_pid = adb('shell', 'pidof', app)
        assert old_pid.isdecimal() and old_pid == snapshot['activityPid']
        adb('shell', 'run-as', app, 'kill', '-9', old_pid)
        deadline = time.monotonic() + 15
        while adb('shell', 'pidof', app, check=False):
            assert time.monotonic() < deadline, 'Target process did not exit'
            time.sleep(0.2)
        # No launcher entry and no new Intent URI grant: the explicit package grant
        # from the now-dead target is the only authority to read the content URI.
        adb('shell', 'am', 'start', '-n', PEER + '/.PeerActivity',
            '-d', snapshot['uri'], '--es', 'run', token, '--ez', 'cold', 'true')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            peer = report(PEER)
            if peer.get('run') == token:
                break
            time.sleep(0.3)
        else:
            raise AssertionError(f'No cold peer report: {report(PEER)}')
        restarted = report(app)
        cold_results = {
            'cold_exact_read': peer.get('read') == 'hello-λ-' + token,
            'cold_write_denied': peer.get('write') == 'DENIED',
            'cold_new_application_pid': restarted.get('startupPid', '').isdecimal()
                and restarted['startupPid'] != old_pid
                and restarted['startupPid'] == adb('shell', 'pidof', app),
            'cold_no_activity_entry': restarted.get('activityPid') == old_pid,
        }
        for name, passed in cold_results.items():
            print(f'{mode} {name}: {"PASS" if passed else "FAIL"}', flush=True)
        assert all(cold_results.values()), (cold_results, restarted, peer)
    finally:
        adb('shell', 'am', 'force-stop', app, check=False)
        adb('shell', 'am', 'force-stop', PEER, check=False)


if __name__ == '__main__':
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('install', '-r', str(ROOT / 'peer/build/outputs/apk/debug/peer-debug.apk'))
    for packaging in ('normal', 'paravoidAndroid'):
        check_mode(packaging)
    print('PASS: 40 cross-UID sharing, activity-result and cold-provider assertions.')
