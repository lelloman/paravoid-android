#!/usr/bin/env python3
"""Real PIN-locked reboot. Only an initially unsecured AVD named paravoid_direct_boot."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PIN = '246810'  # Public disposable-fixture credential, never a user's PIN.


def adb(*args, check=True):
    result = subprocess.run(['adb', '-s', os.environ['ANDROID_SERIAL'], *args],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stdout}')
    return result.stdout.strip()


def wait(label, predicate, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + label)


def user_state():
    return adb('shell', 'am', 'get-started-user-state', '0', check=False)


def boot_changed(old_boot):
    if adb('shell', 'getprop', 'sys.boot_completed', check=False) != '1':
        return False
    current = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id', check=False)
    return bool(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', current)) and current != old_boot


def observation(app, run, kind, boot=None):
    raw = adb('logcat', '-d', '-v', 'raw', '-s', 'DirectBootProbe:I', '*:S')
    found = None
    for line in raw.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if value.get('app') == app and value.get('run') == run:
            assert 'error' not in value, value
            if value.get('kind') == kind and (boot is None or value.get('boot') == boot):
                found = value
    return found


def verify_locked(value, previous_boot):
    assert value['unlocked'] is False and value['noActivity'] is True, value
    assert value['loader'] is True and value['boot'] > previous_boot, value
    assert value['count'] == 1, value


def unlock():
    if user_state() == 'RUNNING_UNLOCKED':
        return
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    # This probe is run on the documented 720x1280 disposable AVD.
    adb('shell', 'input', 'swipe', '360', '1100', '360', '250', '300')
    time.sleep(1)
    adb('shell', 'input', 'text', PIN)
    adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
    wait('PIN unlock', lambda: user_state() == 'RUNNING_UNLOCKED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-pin-and-reboot', action='store_true', required=True)
    parser.parse_args()
    assert os.environ.get('ANDROID_SERIAL', '').startswith('emulator-')
    assert adb('emu', 'avd', 'name').splitlines()[0] == 'paravoid_direct_boot'
    assert adb('shell', 'wm', 'size') == 'Physical size: 720x1280', 'Expected documented lock-screen geometry'
    assert adb('shell', 'getprop', 'ro.crypto.type') == 'file', 'Real file-based encryption required'
    assert adb('shell', 'am', 'get-current-user') == '0'
    assert user_state() == 'RUNNING_UNLOCKED', 'Start with an unlocked fresh AVD'
    # Fails before changing anything if the device already has a credential.
    assert 'verified successfully' in adb('shell', 'locksettings', 'verify').lower()
    previous_disabled = adb('shell', 'locksettings', 'get-disabled')
    cases = []
    pin_set = False
    try:
        for mode, suffix in (('normal', 'normal'), ('paravoidAndroid', 'paravoid')):
            app = 'com.lelloman.paravoidcompat.directboot.' + suffix
            apk = ROOT / f'build/outputs/apk/{mode}/debug/direct-boot-compatibility-{mode}-debug.apk'
            adb('install', '--no-streaming', '-r', str(apk))
            cases.append((app, uuid.uuid4().hex, mode))
            adb('shell', 'pm', 'clear', app)
            adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'allow')
        result = adb('shell', 'locksettings', 'set-pin', PIN)
        pin_set = True
        assert 'pin set to' in result.lower(), result
        initial = {}
        for app, run, mode in cases:
            launcher = ('com.lelloman.paravoidandroid.runtime.LauncherActivity' if mode == 'paravoidAndroid'
                        else 'com.lelloman.paravoidcompat.directboot.ProbeActivity')
            adb('shell', 'am', 'start', '-n', app + '/' + launcher, '--es', 'probeRun', run)
            initial[app] = wait('prepared data', lambda: observation(app, run, 'prepared'))
            assert initial[app]['unlocked'], initial[app]
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        old_boot = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        adb('shell', 'svc', 'power', 'reboot', check=False)
        wait('real reboot', lambda: boot_changed(old_boot), 180)
        assert user_state() == 'RUNNING_LOCKED', user_state()
        current_boot = int(adb('shell', 'settings', 'get', 'global', 'boot_count'))
        for app, run, mode in cases:
            application = wait('locked Application', lambda: observation(app, run, 'application', current_boot))
            locked = wait('LOCKED_BOOT_COMPLETED', lambda: observation(app, run, 'locked', current_boot))
            alarm = wait('alarm before unlock', lambda: observation(app, run, 'alarm', current_boot))
            for value in (application, locked, alarm):
                verify_locked(value, initial[app]['boot'])
            assert locked['credentialRejected'] and locked['deviceData'] and alarm['parcel'], (locked, alarm)
            assert application['pid'] == locked['pid'], (application, locked)
            assert not observation(app, run, 'initialized', current_boot) and not observation(app, run, 'bootCompleted', current_boot)
            assert user_state() == 'RUNNING_LOCKED'
            print(f'PASS {mode}: PIN-locked real boot, cold payload Application/receiver, DE data, CE rejection, alarm Parcelable', flush=True)
        unlock()
        for app, run, mode in cases:
            completed = wait('BOOT_COMPLETED', lambda: observation(app, run, 'bootCompleted', current_boot))
            initialized = wait('deferred initialization', lambda: observation(app, run, 'initialized', current_boot))
            wait('both unlock paths', lambda: (value := observation(app, run, 'unlockAttempt', current_boot))
                 and value['count'] == 2)
            assert completed['unlocked'] and completed['noActivity'] and completed['count'] == 1, completed
            assert initialized['credential'] and initialized['count'] == 1 and initialized['unlocked'], initialized
            assert observation(app, run, 'application', current_boot)['count'] == 1, 'Application unexpectedly restarted'
            # After unlock, independently inspect persisted DE observations, not only logcat.
            raw = adb('shell', 'run-as', app, 'cat', '/data/user_de/0/' + app + '/shared_prefs/direct-boot.xml')
            persisted = {node.get('name'): node.text if node.tag == 'string' else node.get('value')
                         for node in ET.fromstring(raw)}
            assert persisted['run'] == run and persisted['initializedCount'] == '1'
            assert persisted['lockedCount'] == persisted['alarmCount'] == persisted['bootCompletedCount'] == '1'
            assert persisted['unlockAttemptCount'] == '2'
            print(f'PASS {mode}: real PIN unlock, CE data restored, Application not rerun, two callbacks/one deferred initialization', flush=True)
    except Exception:
        print(adb('logcat', '-d', '-v', 'brief', '-s', 'DirectBootProbe', 'AndroidRuntime', 'ParavoidAndroid', check=False), flush=True)
        raise
    finally:
        if pin_set:
            unlock()
            adb('shell', 'locksettings', 'clear', '--old', PIN)
            adb('shell', 'locksettings', 'set-disabled', previous_disabled)
        for app, _, _ in cases:
            adb('shell', 'am', 'force-stop', app, check=False)
            adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'default', check=False)


if __name__ == '__main__':
    main()
