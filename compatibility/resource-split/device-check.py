#!/usr/bin/env python3
"""Fixed installed pinned table; cold none/A/B/A resource selection in both modes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
APP = 'com.lelloman.paravoidcompat.resourcesplit'


def adb(*args, data=None, binary=False, check=True):
    result = subprocess.run(['adb', '-s', os.environ['ANDROID_SERIAL'], *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stderr.decode()} {result.stdout.decode(errors="replace")}')
    return result.stdout if binary else result.stdout.decode().strip()


def wait_for(label, read):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        value = read()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError('Timed out: ' + label)


def prefs():
    raw = adb('shell', 'run-as', APP, 'cat', 'shared_prefs/split-probe.xml', check=False)
    try:
        return {node.get('name'): node.text if node.tag == 'string' else node.get('value')
                for node in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def stage(name, data, readonly=True):
    path = 'files/split/' + name
    adb('shell', '-T', 'run-as', APP, 'tee', path + '.tmp', data=data, binary=True)
    adb('shell', 'run-as', APP, 'chmod', '444' if readonly else '600', path + '.tmp')
    adb('shell', 'run-as', APP, 'mv', path + '.tmp', path)


def validate(mode, version, result, observed, token):
    title = 'absent' if version == 'none' else 'Payload ' + version
    assert result['run'] == token and result['title'] == title, result
    assert result['shell'] == (mode == 'paravoidAndroid'), result
    assert result['shellLabel'] == 'Resource split probe' and result['pinnedTheme'] is True, result
    assert result['libraryLoader'] is True and result['titleId'] >> 24 == 0x7f, result
    assert observed['providerTitle'] == observed['applicationTitle'] == title, observed
    assert observed['providerPid'] == str(result['pid']), observed
    assert observed['providerBeforeApplication'] == 'true', observed
    assert result['constructorTitle'] == (title if mode == 'paravoidAndroid' else 'not-attached'), result
    assert result['removed'] == ('Removed after A' if version == 'A' else 'absent'), result
    assert result['added'] == ('Added in B' if version == 'B' else 'absent'), result
    if version != 'none':
        assert result['view'] == title and result['italian'] == 'Risorse ' + version, result
        assert result['library'] == result['styleable'] == 'Library resource', result
        assert result['payloadTheme'] is True, result
        assert result['accent'] == (0xff006600 if version == 'A' else 0xff000099) - 2**32, result


def inspect_apks(sdk):
    aapt = Path(sdk) / 'build-tools/35.0.0/aapt2'
    for mode in ('normal', 'paravoidAndroid'):
        apk = ROOT / f'build/outputs/apk/{mode}/debug/resource-split-{mode}-debug.apk'
        dump = subprocess.check_output([str(aapt), 'dump', 'resources', str(apk)], text=True)
        assert f'Package name={APP} id=7f' in dump, dump
        assert 'string/shell_label' in dump and 'style/ShellTheme' in dump, dump
        for movable in ('string/title', 'string/removed', 'string/library_message', 'layout/panel', 'style/PayloadTheme'):
            assert movable not in dump, (mode, movable)
        with zipfile.ZipFile(apk) as archive:
            assert not any(name.endswith('panel.xml') for name in archive.namelist())
    print('PASS installed artifacts: both tables are pinned-only, package 0x7f', flush=True)


def check_mode(mode):
    apk = ROOT / f'build/outputs/apk/{mode}/debug/resource-split-{mode}-debug.apk'
    launcher = ('com.lelloman.paravoidandroid.runtime.LauncherActivity' if mode == 'paravoidAndroid'
                else APP + '.ProbeActivity')
    adb('install', '-r', str(apk))
    adb('shell', 'pm', 'clear', APP)
    adb('shell', 'run-as', APP, 'mkdir', '-p', 'files/split')
    path = adb('shell', 'pm', 'path', APP).removeprefix('package:')
    def identity():
        assert adb('shell', 'pm', 'path', APP) == 'package:' + path
        return hashlib.sha256(adb('exec-out', 'cat', path, binary=True)).hexdigest()
    installed = identity()
    for version in ('A', 'B'):
        stage(version + '.apk', (ROOT / f'build/packs/{version}.apk').read_bytes())
    pids = set()
    title_id = None
    try:
        for version in ('none', 'A', 'B', 'A'):
            adb('shell', 'am', 'force-stop', APP)
            assert not adb('shell', 'pidof', APP, check=False)
            stage('selected', version.encode())
            token = uuid.uuid4().hex
            adb('shell', 'am', 'start', '-n', APP + '/' + launcher, '--es', 'run', token)
            def fresh():
                observed = prefs()
                assert not observed.get('error'), observed
                result = json.loads(observed.get('report', '{}'))
                return (result, observed) if result.get('run') == token else None
            result, observed = wait_for(mode + '/' + version, fresh)
            validate(mode, version, result, observed, token)
            assert result['pid'] not in pids and str(result['pid']) == adb('shell', 'pidof', APP), result
            pids.add(result['pid'])
            if title_id is None: title_id = result['titleId']
            assert result['titleId'] == title_id and identity() == installed
            checks = 'pinned-only negative control' if version == 'none' else 'library R/styleable, XML/theme/locale, add/remove'
            print(f'PASS {mode}/{version}: {checks}, early Application/provider, fixed APK', flush=True)
        for writable, corrupt, expected in ((False, True, 'hash mismatch'), (True, False, 'read-only')):
            adb('shell', 'am', 'force-stop', APP)
            old_failure = prefs().get('failurePid')
            contents = (ROOT / 'build/packs/B.apk').read_bytes() + (b'corrupt' if corrupt else b'')
            stage('B.apk', contents, readonly=not writable)
            stage('selected', b'B')
            adb('shell', 'am', 'start', '-n', APP + '/' + launcher)
            failure = wait_for(expected, lambda: value if (value := prefs()).get('failurePid') not in (None, old_failure) else None)
            assert expected in failure['failure'], failure
            assert str(json.loads(failure['report'])['pid']) != failure['failurePid'], failure
            assert identity() == installed
            print(f'PASS {mode}: rejects {expected}', flush=True)
    finally:
        adb('shell', 'am', 'force-stop', APP, check=False)


if __name__ == '__main__':
    if not os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'):
        raise SystemExit('Set ANDROID_SERIAL to a dedicated emulator')
    inspect_apks(os.environ['ANDROID_HOME'])
    try:
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        for packaging in ('normal', 'paravoidAndroid'):
            check_mode(packaging)
    except Exception:
        print(adb('logcat', '-d', '-t', '120', 'AndroidRuntime:E', 'ParavoidAndroid:E', '*:S'))
        raise
