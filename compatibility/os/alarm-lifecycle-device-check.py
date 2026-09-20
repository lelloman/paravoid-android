#!/usr/bin/env python3
"""Mutates idle/battery state and can reboot: ONLY a disposable, dedicated emulator."""
import argparse
import json
import os
import re
import time
import uuid
from integration_driver import adb, report, wait_for, kill_target, install, launch, cleanup


def event(app, key, run):
    values = report(app)
    assert 'lifecycleError' not in values and 'alarm_error' not in values, values
    value = json.loads(values.get(key, '{}'))
    assert 'error' not in value, value
    return value if value.get('run') == run else None


def pending(app):
    # Active alarm headers, not historical delivery statistics or PendingIntent records.
    return len(re.findall(r'^\s*ELAPSED_WAKEUP #\d+: Alarm\{[^\n]* ' + re.escape(app) + r'\}',
                          adb('shell', 'dumpsys', 'alarm'), re.MULTILINE))


def start(mode, scenario):
    app = install(mode)
    adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'allow')
    run = uuid.uuid4().hex
    launch(app, mode, 'alarmLifecycle', run, '--es', 'alarmMode', scenario)
    scheduled = wait_for('persisted schedule', lambda: event(app, 'lifecycleScheduled', run))
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    assert pending(app) == (2 if scenario == 'idle' else 1)
    return app, run, scheduled


def received(app, kind, run):
    value = event(app, 'alarm_' + kind, run)
    if value:
        assert value['parcel'] and value['loader'] and value['noActivity'], value
        assert report(app)['alarmCount_' + kind] == '1', report(app)
    return value


def finish(app):
    cleanup(app)
    adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'default', check=False)


def revoke(mode):
    app, run, scheduled = start(mode, 'revoke')
    try:
        adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'deny')
        wait_for('revocation kills app', lambda: not adb('shell', 'pidof', app, check=False))
        wait_for('pending exact alarm removed', lambda: pending(app) == 0)
        # No manual kill, force-stop, Activity relaunch or synthetic permission broadcast.
        adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'allow')
        restored = wait_for('system grant receiver', lambda: event(app, 'lifecycleRecovered', run))
        assert restored['action'] == 'android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED'
        assert restored['noActivity'] and restored['pid'] != scheduled['pid'], restored
        wait_for('recovered alarm', lambda: received(app, 'recovered', run))
        # Remain beyond the original deadline, not just the recovery alarm deadline.
        wait_for('old deadline passes', lambda: float(adb('shell', 'cat', '/proc/uptime').split()[0])
                 * 1000 > scheduled['at'] + 23000)
        assert 'alarm_revoked' not in report(app), report(app)
        assert report(app)['activityPid'] == str(scheduled['pid']), report(app)
        print(f'PASS {mode}: revocation kills process/removes pending alarm; real grant broadcast restores schedule cold', flush=True)
    finally:
        finish(app)


def idle(mode):
    app, run, scheduled = start(mode, 'idle')
    try:
        kill_target(app, scheduled['pid'])
        adb('shell', 'dumpsys', 'battery', 'unplug')
        adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        adb('shell', 'dumpsys', 'deviceidle', 'force-idle')
        assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep') == 'IDLE'
        delivered = wait_for('allow-while-idle alarm', lambda: received(app, 'idle', run))
        assert delivered['idle'] and delivered['pid'] != scheduled['pid'], delivered
        assert delivered['at'] >= scheduled['at'] + 20000, delivered
        assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep') == 'IDLE'
        assert 'alarm_ordinary' not in report(app), report(app)
        assert pending(app) >= 1, 'Ordinary alarm disappeared instead of being deferred'
        released_at = float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000
        adb('shell', 'dumpsys', 'deviceidle', 'unforce')
        adb('shell', 'dumpsys', 'battery', 'reset')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        wait_for('deep idle exits', lambda: adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep') == 'ACTIVE')
        ordinary = wait_for('ordinary alarm after idle exit', lambda: received(app, 'ordinary', run))
        # Alarm dispatch and PowerManager idle-state notification are asynchronous:
        # an ordinary receiver may still see idle=true during the exit transition.
        assert ordinary['at'] >= released_at and ordinary['at'] >= delivered['at'], ordinary
        print(f'PASS {mode}: forced deep idle permits allow-while-idle cold delivery, defers ordinary alarm until exit', flush=True)
    finally:
        adb('shell', 'dumpsys', 'deviceidle', 'unforce', check=False)
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', check=False)
        adb('shell', 'wm', 'dismiss-keyguard', check=False)
        finish(app)


def reboot():
    cases = []
    try:
        for mode in ('normal', 'paravoidAndroid'):
            app, run, scheduled = start(mode, 'boot')
            cases.append((mode, app, run, scheduled))
            kill_target(app, scheduled['pid'])
        old_boot = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        for _, app, _, _ in cases:
            state = adb('shell', 'dumpsys', 'package', app)
            assert re.search(r'User 0:.*stopped=false.*notLaunched=false', state), state
        # A framework reboot flushes package/usage state. Direct `adb reboot`
        # immediately after pm clear/first launch can race those asynchronous writes.
        # The shell transport can disappear before svc returns; boot ID below
        # proves acceptance instead of relying on its exit status.
        adb('shell', 'svc', 'power', 'reboot', check=False)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if adb('shell', 'getprop', 'sys.boot_completed', check=False) == '1':
                current = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id', check=False)
                if current and current != old_boot:
                    break
            time.sleep(1)
        else:
            raise AssertionError('Emulator did not complete a real reboot')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        for mode, app, run, scheduled in cases:
            state = adb('shell', 'dumpsys', 'package', app)
            assert re.search(r'User 0:.*stopped=false.*notLaunched=false', state), state
            restored = wait_for('BOOT_COMPLETED receiver', lambda: event(app, 'lifecycleRecovered', run))
            assert restored['action'] == 'android.intent.action.BOOT_COMPLETED', restored
            assert restored['noActivity'] and restored['boot'] > scheduled['boot'], restored
            alarm = wait_for('rescheduled post-boot alarm', lambda: received(app, 'afterBoot', run))
            assert alarm['boot'] == restored['boot'], alarm
            assert alarm['at'] >= restored['at'] + restored['delay'] - 100, alarm
            values = report(app)
            assert values['activityPid'] == str(scheduled['pid']) and 'alarm_beforeBoot' not in values, values
            print(f'PASS {mode}: real reboot, BOOT_COMPLETED cold payload entry, persisted schedule resubmitted and delivered without Activity', flush=True)
    finally:
        for _, app, _, _ in cases:
            finish(app)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario', choices=('revoke', 'idle', 'reboot'))
    args = parser.parse_args()
    assert os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'), 'Dedicated disposable emulator required'
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 31
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    if args.scenario == 'reboot':
        reboot()
    else:
        for mode in ('normal', 'paravoidAndroid'):
            (revoke if args.scenario == 'revoke' else idle)(mode)
