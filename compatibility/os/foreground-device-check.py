#!/usr/bin/env python3
"""Foreground service with Parcelable start/redelivery and explicit stop/restart."""
import json
import time
import uuid
from integration_driver import adb, report, ROOT, PEER, wait_for, tap, install, prepare, cleanup


def snapshot(app, run, starts):
    prefs = report(app)
    assert not prefs.get('foregroundError'), prefs
    assert not report(PEER).get('foregroundPeerError'), report(PEER)
    value = json.loads(prefs.get('foreground', '{}'))
    return value if value.get('run') == run and value.get('starts') == starts else None


def verify(app, value, redelivered):
    assert all(value[key] is True for key in ('notification', 'loader', 'type')), value
    assert value['redelivered'] is redelivered, value
    assert str(value['pid']) == report(app)['startupPid'] == adb('shell', 'pidof', app), value
    assert 'activityPid' not in report(app), 'Target Activity unexpectedly launched'
    assert adb('shell', 'run-as', app, 'cat', 'files/foreground-checkpoint.txt') == value['run']


def stop(app, instance):
    tap('Stop foreground service')
    wait_for('foreground destruction', lambda: report(app).get('foregroundDestroyed') == instance)
    assert report(app)['foregroundNotificationRemoved'] == 'true', report(app)


def check_mode(mode):
    app = install(mode)
    adb('install', '-r', str(ROOT / 'peer/build/outputs/apk/debug/peer-debug.apk'))
    token = 'λ-' + uuid.uuid4().hex[:12]
    try:
        adb('shell', 'pm', 'grant', app, 'android.permission.POST_NOTIFICATIONS')
        assert not adb('shell', 'pidof', app, check=False)
        adb('shell', 'am', 'start', '-W', '-n', PEER + '/.PeerActivity',
            '--es', 'scenario', 'foreground', '--es', 'target', app, '--es', 'run', token)
        peer = wait_for('visible peer ready', lambda: value if (value := report(PEER)).get('foregroundReady') == token else None)
        tap('Start foreground service')
        first = wait_for('cold foreground service', lambda: snapshot(app, token, 1))
        verify(app, first, False)
        pid = adb('shell', 'pidof', app)
        assert pid.isdecimal() and pid == str(first['pid'])
        adb('shell', 'run-as', app, 'kill', '-9', pid)
        second = wait_for('foreground Intent redelivery', lambda: snapshot(app, token, 2))
        verify(app, second, True)
        assert second['pid'] != first['pid'] and second['instance'] != first['instance'], second
        assert adb('shell', 'pidof', PEER) == peer['foregroundPeerPid'], 'Peer restarted'
        stop(app, second['instance'])
        tap('Start foreground service')
        third = wait_for('explicit fresh start', lambda: snapshot(app, token, 3))
        verify(app, third, False)
        assert third['instance'] != second['instance']
        stop(app, third['instance'])
        time.sleep(1)
        assert report(app)['foregroundStarts'] == '3', report(app)
        print(f'PASS {mode}: cold dataSync foreground start, notification/type, payload Parcelable redelivery in new process, stop/removal and explicit fresh restart', flush=True)
    finally:
        cleanup(app)


if __name__ == '__main__':
    prepare()
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 34, 'Use API 34+ for the foreground permission/type probe'
    for packaging in ('normal', 'paravoidAndroid'):
        check_mode(packaging)
