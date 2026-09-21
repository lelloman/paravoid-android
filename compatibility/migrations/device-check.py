#!/usr/bin/env python3
"""Two Room versions, fixed shell APK; fixture-only prebundled code selection."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'build/experiment'


def adb(*args, check=True, input=None):
    result = subprocess.run(['adb', '-s', os.environ['ANDROID_SERIAL'], *args], input=input,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stdout}')
    return result.stdout.strip()


def report(app, request):
    raw = adb('shell', 'run-as', app, 'cat', 'shared_prefs/migration-probe.xml', check=False)
    try:
        values = {node.get('name'): node.text for node in ET.fromstring(raw)}
        value = json.loads(values.get('result', '{}'))
    except (ET.ParseError, json.JSONDecodeError):
        return None
    return value if value.get('request') == request else None


def verify(value, run, version, phase):
    assert value['run'] == run and value['phase'] == phase and value['version'] == version, value
    assert 'error' not in value and value.get('passed') is True, value
    assert value['databaseVersion'] == (1 if phase == 'seed' else 2), value
    assert value['rows'] == (2 if phase == 'seed' else 3), value
    assert value['migrations'] == (1 if phase == 'upgrade' else 0), value
    if phase == 'upgrade':
        assert value['transactionRollback'] and value['uniqueIndex'], value
    if phase == 'downgrade':
        assert value['downgradeRejected'] and 'from 2 to 1' in value['reason'], value


def installed_identity(app):
    paths = adb('shell', 'pm', 'path', app).splitlines()
    assert len(paths) == 1, paths
    path = paths[0].removeprefix('package:')
    digest = adb('shell', 'sha256sum', path).split()[0]
    return path, digest


def check(shell):
    app = 'com.lelloman.paravoidcompat.migrations.' + ('paravoid' if shell else 'normal')
    adb('install', '--no-streaming', '-r', '-d', str(OUT / ('shell.apk' if shell else 'normal-v1.apk')))
    adb('shell', 'pm', 'clear', app)  # Exactly once, before creating any test data.
    run = uuid.uuid4().hex
    original = installed_identity(app)
    if shell:
        assert original[1] == hashlib.sha256((OUT / 'shell.apk').read_bytes()).hexdigest()
    seen_pids = set()
    try:
        for version, phase in ((1, 'seed'), (2, 'upgrade'), (2, 'restart'), (1, 'downgrade'), (2, 'recover')):
            adb('shell', 'am', 'force-stop', app)
            assert not adb('shell', 'pidof', app, check=False)
            if shell:
                adb('shell', 'run-as', app, 'mkdir', '-p', 'files')
                adb('shell', 'run-as', app, 'tee', 'files/migration-version', input=str(version) + '\n')
                assert installed_identity(app) == original, 'Shell APK changed between versions'
            elif phase in ('upgrade', 'downgrade', 'recover'):
                adb('install', '--no-streaming', '-r', '-d', str(OUT / f'normal-v{version}.apk'))
            request = uuid.uuid4().hex
            launcher = ('com.lelloman.paravoidandroid.runtime.LauncherActivity' if shell
                        else 'com.lelloman.paravoidcompat.migrations.ProbeActivity')
            adb('shell', 'am', 'start', '-f', '0x10008000', '-n', app + '/' + launcher,
                '--es', 'run', run, '--es', 'phase', phase, '--es', 'request', request)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                value = report(app, request)
                if value:
                    break
                time.sleep(0.25)
            else:
                raise AssertionError('Timed out: ' + phase)
            verify(value, run, version, phase)
            assert value['pid'] not in seen_pids, 'Expected fresh process for each code activation'
            assert str(value['pid']) == adb('shell', 'pidof', app), value
            seen_pids.add(value['pid'])
            print(f'PASS {"shell" if shell else "normal"}/{phase}: code v{version}, database v{value["databaseVersion"]}, rows={value["rows"]}, migrations={value["migrations"]}', flush=True)
        if shell:
            assert installed_identity(app) == original
    except Exception:
        print(adb('logcat', '-d', '-v', 'brief', '-s', 'AndroidRuntime', 'ParavoidAndroid', check=False), flush=True)
        raise
    finally:
        adb('shell', 'am', 'force-stop', app, check=False)


if __name__ == '__main__':
    assert os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'), 'Use a dedicated emulator'
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    check(False)
    check(True)
