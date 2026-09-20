#!/usr/bin/env python3
"""Real exact alarms after process death; dedicated unlocked API 31+ emulator."""
import json
import os
import time
import uuid
from integration_driver import adb, report, wait_for, kill_target, install, launch, cleanup


def observation(app, key, run):
    data = json.loads(report(app).get(key, '{}'))
    assert 'error' not in data, data
    return data if data.get('run') == run else None


def check_mode(mode):
    app = install(mode)
    try:
        # Fixture-only app-op controls exercise enforcement, not the Settings UI.
        for permission in ('deny', 'allow'):
            adb('shell', 'am', 'force-stop', app)
            adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', permission)
            run = uuid.uuid4().hex
            launch(app, mode, 'alarm', run)
            scheduled = wait_for('alarm schedule', lambda: observation(app, 'alarmScheduled', run))
            assert scheduled['allowed'] == (permission == 'allow'), scheduled
            if permission == 'deny':
                assert scheduled['rejected'] is True, scheduled
                print(f'PASS {mode}: exact-alarm denied access reports false and throws SecurityException', flush=True)
                continue
            assert scheduled['sameToken'] is True, scheduled
            old_pid = str(scheduled['pid'])
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            # Observe registration before killing; never use force-stop for delivery proof.
            dump = adb('shell', 'dumpsys', 'alarm')
            assert f'tag=*walarm*:{app}/com.lelloman.paravoidcompat.os.AlarmReceiver' in dump, dump
            kill_target(app, old_pid)
            delivered = wait_for('cold alarm', lambda: observation(app, 'alarm_cold', run))
            assert str(delivered['pid']) != old_pid, delivered
            assert delivered['parcel'] and delivered['loader'], delivered
            assert report(app)['startupPid'] == str(delivered['pid']), delivered
            assert report(app)['activityPid'] == old_pid, 'Alarm unexpectedly launched Activity'
            wait_for('later sentinel alarm', lambda: observation(app, 'alarm_sentinel', run))
            # Bounded negative observation, not a promise of infinite non-execution.
            time.sleep(3)
            values = report(app)
            assert 'alarm_cancelled' not in values and 'alarm_error' not in values, values
            for kind, offset in (('cold', 15000), ('replacement', 20000), ('sentinel', 30000)):
                event = observation(app, 'alarm_' + kind, run)
                assert event and event['parcel'] and event['loader'], event
                assert event['at'] >= scheduled['start'] + offset, event
                assert values['alarmCount_' + kind] == '1', values
            print(f'PASS {mode}: real cold alarm/Parcelable, same-token replacement, cancellation and later sentinel', flush=True)
    finally:
        cleanup(app)
        adb('shell', 'cmd', 'appops', 'set', app, 'SCHEDULE_EXACT_ALARM', 'default', check=False)


if __name__ == '__main__':
    assert os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'), 'Use a dedicated emulator'
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 31, 'Permission probe requires API 31+'
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    for mode in ('normal', 'paravoidAndroid'):
        check_mode(mode)
