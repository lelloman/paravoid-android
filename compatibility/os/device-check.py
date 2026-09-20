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
        return {node.get('name'): node.text for node in ET.fromstring(raw)}
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
        adb('shell', 'am', 'start', '-W', '-n', app + '/' + launcher, '--es', 'probeRun', token)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            snapshot = report(app)
            if snapshot.get('run') == token:
                results = json.loads(snapshot['results'])
                for name, value in results.items():
                    print(f'{mode} {name}: {value}', flush=True)
                assert len(results) == 16 and all(value == 'PASS' for value in results.values()), results
                return
            time.sleep(0.3)
        raise AssertionError(f'Timed out waiting for {mode}: {report(app)}')
    finally:
        adb('shell', 'am', 'force-stop', app, check=False)
        adb('shell', 'am', 'force-stop', PEER, check=False)


if __name__ == '__main__':
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('install', '-r', str(ROOT / 'peer/build/outputs/apk/debug/peer-debug.apk'))
    for packaging in ('normal', 'paravoidAndroid'):
        check_mode(packaging)
    print('PASS: 32 cross-UID sharing and activity-result assertions.')
