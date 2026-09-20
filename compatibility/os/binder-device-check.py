#!/usr/bin/env python3
"""Real remote AIDL, cold service entry, lifecycle and Binder death checks."""
import json
import uuid
from integration_driver import adb, report, PEER, wait_for, tap, install, prepare, cleanup


def phase(number, token):
    prefs = report(PEER)
    assert not prefs.get('binderError'), prefs
    result = json.loads(prefs.get('binder' + str(number), '{}'))
    return result if result.get('run') == token else None


def verify(app, result):
    for key in ('remote', 'uid', 'loader', 'text', 'callback', 'list', 'null', 'exception'):
        assert result[key] is True, (key, result)
    assert str(result['pid']) == adb('shell', 'pidof', app) == report(app)['startupPid'], result
    assert str(result['peerPid']) == adb('shell', 'pidof', PEER), result
    assert 'activityPid' not in report(app), 'Target Activity unexpectedly entered'


def check_mode(mode):
    app = install(mode)
    token = uuid.uuid4().hex
    try:
        assert not adb('shell', 'pidof', app, check=False), 'Target must start cold'
        adb('shell', 'am', 'start', '-W', '-n', PEER + '/.PeerActivity',
            '--es', 'scenario', 'binder', '--es', 'target', app, '--es', 'run', token)
        first = wait_for('first remote connection', lambda: phase(1, token))
        verify(app, first)
        tap('Unbind service')
        wait_for('service destroyed after final unbind', lambda: report(app).get('binderDestroyed') == first['instance'])
        assert report(PEER).get('binderDisconnected') is None, 'Normal unbind is not disconnection'
        tap('Bind service')
        second = wait_for('explicit rebind', lambda: phase(2, token))
        verify(app, second)
        assert second['instance'] != first['instance'], 'Service instance was reused after destruction'
        assert second['peerPid'] == first['peerPid'], 'Peer process changed'
        print(f'PASS {mode}: cold remote AIDL, typed Parcelables/callback/list/null/exception/UID/loaders, final unbind and new service instance', flush=True)
        # Keep the binding and peer alive. Force-stop would change binding semantics;
        # kill only the verified service PID. Auto-restart can be too fast to sample
        # an empty pidof, so prove death via DeathRecipient and a different new PID.
        pid = adb('shell', 'pidof', app)
        assert pid.isdecimal() and pid == str(second['pid'])
        adb('shell', 'run-as', app, 'kill', '-9', pid)
        death = wait_for('DeathRecipient', lambda: value if (value := report(PEER)).get('binderDeathRun') == token else None)
        assert death['binderDead'] == 'true' and death['binderDeadCall'] == 'true', death
        wait_for('onServiceDisconnected', lambda: report(PEER).get('binderDisconnected') == token)
        third = wait_for('automatic service reconnection', lambda: phase(3, token))
        verify(app, third)
        assert third['pid'] != second['pid'] and third['instance'] != second['instance'], third
        assert third['peerPid'] == first['peerPid'], 'Recovery restarted the client'
        tap('Unbind service')
        wait_for('restarted service destroyed', lambda: report(app).get('binderDestroyed') == third['instance'])
        print(f'PASS {mode}: DeathRecipient, dead-token RemoteException, disconnection, automatic new-process rebind and clean final unbind', flush=True)
    finally:
        cleanup(app)


if __name__ == '__main__':
    prepare()
    for packaging in ('normal', 'paravoidAndroid'):
        check_mode(packaging)
