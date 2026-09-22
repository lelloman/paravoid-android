#!/usr/bin/env python3
"""Production relocated-native gate. Installs only the JNI fixture on a dedicated emulator."""
import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('jni_device', ROOT / 'device-check.py')
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


def check_cache(app, apk, abi):
    with ZipFile(apk) as archive:
        identity = archive.read('assets/paravoid/native-libraries.sha256').decode().strip()
        with ZipFile(io.BytesIO(archive.read('assets/paravoid/native-libraries.zip'))) as native:
            content = native.read('lib/' + abi + '/libprobe_jni.so')
    cache = 'no_backup/paravoid-native-libraries/' + identity + '.zip.libs/' + abi + '/libprobe_jni.so'
    def replace(data, mode):
        device.adb('shell', 'am', 'force-stop', app)
        subprocess.run(['adb', '-s', os.environ['ANDROID_SERIAL'], 'shell', '-T', 'run-as', app,
                        'tee', cache + '.probe'], input=data, stdout=subprocess.DEVNULL, check=True)
        device.adb('shell', 'run-as', app, 'chmod', mode, cache + '.probe')
        device.adb('shell', 'run-as', app, 'mv', cache + '.probe', cache)
    launcher = app + '/com.lelloman.paravoidandroid.runtime.LauncherActivity'
    for reason, data, mode in [('Native library hash mismatch', bytes([content[0] ^ 1]) + content[1:], '444'),
                               ('Writable native library cache', content, '600')]:
        replace(data, mode)
        old = device.prefs(app).get('report')
        device.adb('logcat', '-c')
        device.adb('shell', 'am', 'start', '-n', launcher)
        device.wait_for(reason, lambda: reason in device.adb('logcat', '-d', 'ParavoidAndroid:E', '*:S'))
        assert device.prefs(app).get('report') == old
        print('PASS native cache rejection: ' + reason, flush=True)
    replace(content, '444')
    device.adb('shell', 'am', 'start', '-n', launcher, '--es', 'probeRun', 'cache-repair')
    restored = device.wait_for('native cache repair', lambda: value if (value := json.loads(device.prefs(app).get('report', '{}'))).get('run') == 'cache-repair' else None)
    assert len(restored['results']) == 13 and set(restored['results'].values()) == {'PASS'}, restored
    device.adb('shell', 'am', 'force-stop', app)
    return {'corruptRejected': True, 'writableRejected': True, 'repaired': restored}


def run(args):
    if args.device and not os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'):
        raise SystemExit('Use a dedicated ANDROID_SERIAL=emulator-...')
    evidence = []
    for storage in ('archive', 'extracted'):
        subprocess.run([str(ROOT.parent.parent / 'gradlew'), '-p', str(ROOT), '-PnativePayload',
            '-PprobeAbis=' + args.abis, '-PnativeLegacyPackaging=' + str(storage == 'extracted').lower(),
            'assembleNormalDebug', 'packageParavoidAndroidDebugParavoidResourceShell', '--console=plain', '--max-workers=2'], check=True)
        normal = ROOT / 'build/outputs/apk/normal/debug/jni-compatibility-normal-debug.apk'
        shell = ROOT / 'build/outputs/paravoid/paravoidAndroidDebug/resource-shell.apk'
        with ZipFile(shell) as archive:
            assert set(n for n in archive.namelist() if n.startswith('lib/')) == {
                'lib/' + abi + '/libparavoid_abi.so' for abi in args.abis.split(',')}
            with ZipFile(io.BytesIO(archive.read('assets/paravoid/native-libraries.zip'))) as native:
                assert set(native.namelist()) == {'lib/' + abi + '/lib' + lib + '.so'
                    for abi in args.abis.split(',') for lib in ('probe_jni', 'probe_dep', 'probe_plugin', 'c++_shared')}
        print('PASS native artifact relocation, exact ABI markers, no installed app-library fallback', flush=True)
        if args.device:
            device.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            device.adb('shell', 'wm', 'dismiss-keyguard')
            for mode, apk in [('normal', normal), ('paravoidAndroid', shell)]:
                failures = device.check_mode(mode, storage, apk)
                assert not failures, failures
                app = 'com.lelloman.paravoidcompat.jni.' + storage + ('.normal' if mode == 'normal' else '.paravoid')
                report = json.loads(device.prefs(app)['report'])
                assert report['abi'] in args.abis.split(','), report
                assert report['process64'] == (report['abi'] in ('x86_64', 'arm64-v8a')), report
                if mode != 'normal': assert '/no_backup/paravoid-native-libraries/' in report['nativeMaps'], report
                evidence.append(dict(mode=mode, storage=storage, report=report))
                if mode != 'normal': evidence[-1]['cacheControls'] = check_cache(app, shell, report['abi'])
    if args.device:
        path = ROOT / ('build/payload-evidence-' + os.environ['ANDROID_SERIAL'] + '-' + args.abis.replace(',', '_') + '.json')
        path.write_text(json.dumps(dict(sdk=device.adb('shell', 'getprop', 'ro.build.version.sdk'),
            sdkFull=device.adb('shell', 'getprop', 'ro.build.version.sdk_full'),
            fingerprint=device.adb('shell', 'getprop', 'ro.build.fingerprint'), abis=args.abis, checks=evidence), indent=2) + '\n')
        print('PASS 104 JNI assertions plus native cache rejection/repair, cold/restored processes and ABI preservation', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', action='store_true')
    parser.add_argument('--abis', default='x86_64,arm64-v8a')
    run(parser.parse_args())
