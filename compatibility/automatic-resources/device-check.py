#!/usr/bin/env python3
"""Dedicated-emulator gate for the plugin-generated resource pair, not a production updater."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
APP = 'com.lelloman.paravoidcompat.automaticresources'
spec = importlib.util.spec_from_file_location('resource_device_helpers', ROOT.parent / 'resource-split/device-check.py')
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
helpers.APP = APP
adb, wait_for, prefs, stage = helpers.adb, helpers.wait_for, helpers.prefs, helpers.stage
OUT = ROOT / 'build/validated'


def validate(mode, version, result):
    shell = mode == 'shell'
    title = 'absent' if version == 'none' else 'Payload ' + version
    assert result['shell'] == shell and result['title'] == title, result
    assert result['shellLabel'] == 'Automatic resource probe' and result['pinnedTheme'], result
    assert result['providerTitle'] == result['applicationTitle'] == title, result
    assert result['providerBeforeApplication'] is True, result
    assert result['constructorTitle'] == (title if shell else 'not-attached'), result
    worker = result['worker']
    assert worker['pid'] != result['pid'] and worker['before'] is True, result
    assert worker['title'] == worker['early'] == worker['application'] == title, result
    assert worker['constructor'] == (title if shell else 'not-attached'), result
    assert result['removed'] == ('Removed after A' if version == 'A' else 'absent'), result
    assert result['added'] == ('Added in B' if version == 'B' else 'absent'), result
    assert result['titleId'] >> 24 == 0x7f, result
    if version == 'none':
        assert result['asset'] == 'absent', result
    else:
        assert result['view'] == title and result['italian'] == 'Risorse ' + version, result
        assert result['library'] == result['styleable'] == 'Library resource', result
        assert result['libraryLoader'] and result['payloadTheme'], result
        assert result['asset'] == 'Asset ' + version, result
        assert result['dayAccent'] == (0xff006600 if version == 'A' else 0xff000099) - 2**32, result
        assert result['nightAccent'] == (0xff44aa44 if version == 'A' else 0xff4444aa) - 2**32, result
        assert result['accent'] == result['nightAccent' if result['night'] else 'dayAccent'], result


def report(token, newer=0):
    observed = prefs()
    assert not observed.get('error'), observed
    result = json.loads(observed.get('report', '{}'))
    return result if result.get('run') == token and result['creations'] > newer else None


def recreate(title):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/paravoid-auto-ui.xml')
    root = ET.fromstring(adb('shell', 'cat', '/data/local/tmp/paravoid-auto-ui.xml'))
    node = next(node for node in root.iter('node') if node.get('text') == title and node.get('clickable') == 'true')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def check_mode(mode):
    adb('install', '-r', str(OUT / f'{mode}.apk'))
    adb('shell', 'pm', 'clear', APP)
    adb('shell', 'run-as', APP, 'mkdir', '-p', 'files/split')
    path = adb('shell', 'pm', 'path', APP).removeprefix('package:')
    def identity():
        assert adb('shell', 'pm', 'path', APP) == 'package:' + path
        return hashlib.sha256(adb('exec-out', 'cat', path, binary=True)).hexdigest()
    installed = identity()
    if mode == 'shell':
        for version in ('A', 'B'):
            stage(version + '.apk', (OUT / f'{version}.apk').read_bytes())
    history, tokens = [], set()
    marker = title_id = None
    try:
        for version in (('none', 'A', 'B', 'A') if mode == 'shell' else ('A',)):
            adb('shell', 'am', 'force-stop', APP)
            adb('shell', 'cmd', 'uimode', 'night', 'no')
            assert not adb('shell', 'pidof', APP, check=False)
            assert not adb('shell', 'pidof', APP + ':worker', check=False)
            if mode == 'shell': stage('selected', version.encode())
            token = uuid.uuid4().hex
            launcher = 'com.lelloman.paravoidandroid.runtime.LauncherActivity' if mode == 'shell' else APP + '.ProbeActivity'
            adb('shell', 'am', 'start', '-W', '-n', APP + '/' + launcher, '--es', 'run', token)
            result = wait_for(mode + '/' + version, lambda: report(token))
            validate(mode, version, result)
            assert result['processToken'] not in tokens and not result['night'], result
            tokens.add(result['processToken'])
            marker, title_id = marker or result['marker'], title_id or result['titleId']
            assert result['marker'] == marker and result['titleId'] == title_id and identity() == installed
            history.append({'mode': mode, 'version': version, 'event': 'cold', 'report': result})
            print(f'PASS {mode}/{version}: cold main + worker, constructor/provider/Application, pinned table/assets, fixed APK/data', flush=True)
            if version != 'none':
                for event in ('recreate', 'night', 'day'):
                    previous = result
                    if event == 'recreate': recreate(result['title'])
                    else: adb('shell', 'cmd', 'uimode', 'night', 'yes' if event == 'night' else 'no')
                    result = wait_for(event, lambda: report(token, previous['creations']))
                    validate(mode, version, result)
                    assert result['pid'] == previous['pid'] and result['processToken'] == previous['processToken'], result
                    assert result['marker'] == marker and result['night'] == (event == 'night'), result
                    assert result['worker']['pid'] == previous['worker']['pid'] and identity() == installed, result
                    history.append({'mode': mode, 'version': version, 'event': event, 'report': result})
                    print(f'PASS {mode}/{version}/{event}: same process, generated R/styleable/XML/theme/assets/configuration contexts', flush=True)
        if mode == 'shell':
            for writable, corrupt, reason in ((False, True, 'hash mismatch'), (True, False, 'read-only')):
                adb('shell', 'am', 'force-stop', APP)
                old_failure = prefs().get('failurePid')
                stage('B.apk', (OUT / 'B.apk').read_bytes() + (b'corrupt' if corrupt else b''), readonly=not writable)
                stage('selected', b'B')
                adb('shell', 'am', 'start', '-n', APP + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
                failed = wait_for(reason, lambda: value if (value := prefs()).get('failurePid') not in (None, old_failure) else None)
                assert reason in failed['failure'] and identity() == installed, failed
                assert str(json.loads(failed['report'])['pid']) != failed['failurePid'], failed
                history.append({'mode': mode, 'event': 'rejected', 'reason': reason})
                print('PASS shell rejects ' + reason, flush=True)
    finally:
        adb('shell', 'am', 'force-stop', APP, check=False)
    return history


if __name__ == '__main__':
    serial = os.environ.get('ANDROID_SERIAL', '')
    if not serial.startswith('emulator-'): raise SystemExit('Set ANDROID_SERIAL to a dedicated emulator; this clears only the fixture app.')
    night = adb('shell', 'cmd', 'uimode', 'night').split()[-1]
    try:
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        checks = check_mode('normal') + check_mode('shell')
        evidence = {'serial': serial, 'sdk': adb('shell', 'getprop', 'ro.build.version.sdk'),
                    'sdkMinor': adb('shell', 'getprop', 'ro.build.version.sdk_minor'),
                    'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint'), 'checks': checks}
        (OUT / f'evidence-{serial}.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(f'PASS {len(checks)} automatic-resource device stages on {serial}', flush=True)
    except Exception:
        print(adb('logcat', '-d', '-t', '140', 'AndroidRuntime:E', 'ParavoidAndroid:E', '*:S'))
        raise
    finally:
        adb('shell', 'am', 'force-stop', APP, check=False)
        if night in ('yes', 'no', 'auto', 'custom'): adb('shell', 'cmd', 'uimode', 'night', night, check=False)
