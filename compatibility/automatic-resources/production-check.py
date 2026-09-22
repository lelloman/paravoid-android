#!/usr/bin/env python3
"""Build/test production embedded resource-shell tasks; no fixture runtime or repacking."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = load('build-fixture')
device = load('device-check')
adb, stage, prefs, wait_for = device.adb, device.stage, device.prefs, device.wait_for
APP = device.APP
OUT = ROOT / 'build/production'


def assemble():
    OUT.mkdir(parents=True, exist_ok=True)
    key = ROOT / 'build/fixture.jks'
    if not key.exists():
        build.run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
                  '-alias', 'fixture', '-dname', 'CN=Paravoid resource fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
    def gradle(*tasks, baseline=False):
        build.gradle('-PproductionRuntime', *tasks, baseline=baseline)
    gradle('exportParavoidAndroidADebugParavoidResourceLedger', 'analyzeParavoidAndroidADebugParavoidResources')
    for version in ('A', 'B'):
        baseline = ROOT / f'build/baseline/paravoidAndroid{version}Debug'
        baseline.mkdir(parents=True, exist_ok=True)
        for name in ('resource-ledger.json', 'resource-boundary.json'):
            shutil.copyfile(build.outputs('A') / 'baseline-candidate' / name, baseline / name)
    gradle('packageParavoidAndroidADebugParavoidResourceShell', 'packageParavoidAndroidBDebugParavoidResourceShell',
           'assembleNormalADebug', 'lintNormalADebug', 'lintParavoidAndroidADebug', baseline=True)
    shutil.copyfile(build.apk('normal'), OUT / 'normal.apk')
    for version in ('A', 'B'):
        shell = build.outputs(version) / 'resource-shell.apk'
        shutil.copyfile(shell, OUT / f'{version}.apk')
        with zipfile.ZipFile(shell) as archive:
            payload = archive.read('assets/paravoid/resources.apk')
            import hashlib
            assert archive.read('assets/paravoid/resources.sha256').decode() == hashlib.sha256(payload).hexdigest() + '\n'
            assert 'assets/content.txt' not in archive.namelist()
            assert not any(name.startswith('res/') for name in archive.namelist())
            dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
            assert b'EmbeddedResources' in dex and b'SplitResources' not in dex
        aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt2'
        dump = subprocess.check_output([aapt, 'dump', 'resources', shell], text=True)
        assert 'string/shell_label' in dump and 'string/title' not in dump and 'layout/panel' not in dump
    print('PASS production artifacts: signed task output, real runtime, pinned-only installed table, embedded resource identity', flush=True)


def launch():
    token = uuid.uuid4().hex
    adb('shell', 'am', 'start', '-W', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity', '--es', 'run', token)
    return token, wait_for('production startup', lambda: device.report(token))


def test_device():
    serial = os.environ.get('ANDROID_SERIAL', '')
    if not serial.startswith('emulator-'): raise SystemExit('Use a dedicated ANDROID_SERIAL=emulator-...')
    night = adb('shell', 'cmd', 'uimode', 'night').split()[-1]
    history = []
    try:
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        device.OUT = OUT
        history.extend(device.check_mode('normal'))
        marker = title_id = None
        tokens = set()
        for index, version in enumerate(('A', 'B', 'A')):
            adb('shell', 'am', 'force-stop', APP)
            adb('install', '-r', str(OUT / f'{version}.apk'))
            if index == 0: adb('shell', 'pm', 'clear', APP)
            adb('shell', 'cmd', 'uimode', 'night', 'no')
            token, result = launch()
            device.validate('shell', version, result)
            marker, title_id = marker or result['marker'], title_id or result['titleId']
            assert result['marker'] == marker and result['titleId'] == title_id
            assert result['processToken'] not in tokens
            tokens.add(result['processToken'])
            history.append({'event': 'install-' + version, 'report': result})
            print('PASS production shell/' + version + ': real early loader, main + worker, preserved data', flush=True)
            for event in ('recreate', 'night', 'day', 'cold-cache'):
                previous = result
                if event == 'recreate': device.recreate(result['title'])
                elif event == 'cold-cache':
                    adb('shell', 'am', 'force-stop', APP)
                    token, result = launch()
                else: adb('shell', 'cmd', 'uimode', 'night', 'yes' if event == 'night' else 'no')
                if event != 'cold-cache': result = wait_for(event, lambda: device.report(token, previous['creations']))
                device.validate('shell', version, result)
                assert result['marker'] == marker and result['night'] == (event == 'night')
                if event == 'cold-cache':
                    assert result['processToken'] not in tokens
                    tokens.add(result['processToken'])
                else:
                    assert result['processToken'] == previous['processToken'] and result['worker']['pid'] == previous['worker']['pid']
                history.append({'event': version + '/' + event, 'report': result})
                print('PASS production ' + version + '/' + event, flush=True)
        with zipfile.ZipFile(OUT / 'A.apk') as archive:
            content = archive.read('assets/paravoid/resources.apk')
            identity = archive.read('assets/paravoid/resources.sha256').decode().strip()
        cache = 'no_backup/paravoid-resources/' + identity + '.apk'
        for reason, data, mode in [('hash mismatch', bytes([content[0] ^ 1]) + content[1:], '444'),
                                   ('writable', content, '600')]:
            adb('shell', 'am', 'force-stop', APP)
            adb('shell', '-T', 'run-as', APP, 'tee', cache + '.probe', data=data, binary=True)
            adb('shell', 'run-as', APP, 'chmod', mode, cache + '.probe')
            adb('shell', 'run-as', APP, 'mv', cache + '.probe', cache)
            old = prefs().get('report')
            adb('logcat', '-c')
            adb('shell', 'am', 'start', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
            wait_for(reason, lambda: reason in adb('logcat', '-d', 'ParavoidAndroid:E', '*:S'))
            assert prefs().get('report') == old
            history.append({'event': 'rejected-cache', 'reason': reason})
            print('PASS production rejects cached ' + reason, flush=True)
        # Leave the fixture usable after the negative controls.
        adb('shell', 'am', 'force-stop', APP)
        adb('shell', '-T', 'run-as', APP, 'tee', cache + '.probe', data=content, binary=True)
        adb('shell', 'run-as', APP, 'chmod', '444', cache + '.probe')
        adb('shell', 'run-as', APP, 'mv', cache + '.probe', cache)
        _, result = launch()
        device.validate('shell', 'A', result)
        history.append({'event': 'cache-repaired', 'report': result})
        evidence = dict(sdk=adb('shell', 'getprop', 'ro.build.version.sdk'), sdkFull=adb('shell', 'getprop', 'ro.build.version.sdk_full'),
                        fingerprint=adb('shell', 'getprop', 'ro.build.fingerprint'), checks=history)
        (OUT / f'evidence-{serial}.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(f'PASS {len(history)} production resource stages on {serial}', flush=True)
    finally:
        adb('shell', 'am', 'force-stop', APP, check=False)
        if night in ('yes', 'no', 'auto', 'custom'): adb('shell', 'cmd', 'uimode', 'night', night, check=False)


if __name__ == '__main__':
    if sys.argv[1:] not in ([], ['--device'], ['--device-only']): raise SystemExit('Usage: production-check.py [--device|--device-only]')
    if '--device-only' not in sys.argv: assemble()
    if len(sys.argv) > 1: test_device()
