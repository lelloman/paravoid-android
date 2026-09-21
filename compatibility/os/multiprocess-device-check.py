#!/usr/bin/env python3
"""Cold :worker startup and Binder recovery with external and same-app clients."""
import json
import os
import uuid
import xml.etree.ElementTree as ET
from integration_driver import adb, report, PEER, ROOT, wait_for, tap, install, prepare, cleanup, launch


def worker_report(app):
    raw = adb('shell', 'run-as', app, 'cat', 'shared_prefs/os-worker.xml', check=False)
    try:
        return {node.get('name'): node.text if node.tag == 'string' else node.get('value')
                for node in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def phase(client, number, token):
    prefs = report(client)
    assert not prefs.get('binderError'), prefs
    result = json.loads(prefs.get('binder' + str(number), '{}'))
    return result if result.get('run') == token else None


def validate(app, result, worker, client_pid, worker_pid):
    for key in ('remote', 'uid', 'loader', 'clientLoader', 'text', 'callback', 'list', 'null',
                'exception', 'bundleRawRejected', 'bundlePrepared'):
        assert result[key] is True, (key, result)
    assert str(result['pid']) == worker_pid == worker['startupPid'], (result, worker)
    assert str(result['peerPid']) == client_pid and client_pid != worker_pid, result
    assert worker['startupProcess'] == app + ':worker', worker
    assert worker['startupCount'] == '1' and worker['startupLoader'] == 'true', worker
    assert worker['startupInstance'], worker
    assert worker['binderActivityCreated'] == 'false', worker
    assert worker['binderBinding'] == result['run'], worker
    assert worker['binderBindingInstance'] == result['instance'], worker


def check_mode(mode, origin):
    app = install(mode)
    adb('install', '-r', str(ROOT / 'peer/build/outputs/apk/debug/peer-debug.apk'))
    token = uuid.uuid4().hex
    client = PEER if origin == 'peer' else app
    boot_receiver = app + '/com.lelloman.paravoidcompat.os.AlarmLifecycleReceiver'
    try:
        # Android 15+ can send BOOT_COMPLETED when a package leaves stopped state.
        # This unrelated fixture receiver otherwise starts the main process while
        # the named-worker-only case is running (also in normal packaging).
        adb('shell', 'pm', 'disable', boot_receiver)
        assert not adb('shell', 'pidof', app, check=False), 'Main must start cold'
        assert not adb('shell', 'pidof', app + ':worker', check=False), 'Worker must start cold'
        extras = ('--es', 'target', app, '--es', 'run', token, '--ez', 'remoteWorker', 'true')
        if origin == 'peer':
            adb('shell', 'am', 'start', '-n', PEER + '/.PeerActivity', '--es', 'scenario', 'binder', *extras)
        else:
            launch(app, mode, 'multiprocess', token, *extras)
        client_pid = None
        main_instance = None
        previous = None
        previous_worker = None
        for number in (1, 2, 3):
            result = wait_for(f'{mode}/{origin} connection {number}', lambda: phase(client, number, token))
            snapshot = worker_report(app)
            live_client = adb('shell', 'pidof', client)
            live_worker = adb('shell', 'pidof', app + ':worker')
            validate(app, result, snapshot, live_client, live_worker)
            if client_pid is None:
                client_pid = live_client
            assert live_client == client_pid, 'Worker recovery restarted the client'
            if origin == 'peer':
                assert not adb('shell', 'pidof', app, check=False), 'Cold worker started main process'
                assert not report(app), 'Main Application or Activity unexpectedly entered'
            else:
                main = report(app)
                assert main['startupPid'] == main['activityPid'] == client_pid, main
                assert main['startupProcess'] == app and main['startupCount'] == '1', main
                assert main['startupLoader'] == 'true', main
                if main_instance is None:
                    main_instance = main['startupInstance']
                assert main['startupInstance'] == main_instance != snapshot['startupInstance'], main
            if previous:
                assert result['instance'] != previous['instance'], 'Service was not recreated'
                if number == 2:
                    assert result['pid'] == previous['pid'], 'Unbind unexpectedly restarted process'
                    assert snapshot['startupInstance'] == previous_worker['startupInstance'], snapshot
                else:
                    assert result['pid'] != previous['pid'], 'Worker process did not change'
                    assert snapshot['startupInstance'] != previous_worker['startupInstance'], snapshot
            print(f'PASS {mode}/{origin}/connection-{number}: independent Application/loader, remote wire checks, client={live_client}, worker={live_worker}', flush=True)
            if number == 2:
                # Kill the exact named worker, never the app's main process or its client.
                assert live_worker.isdecimal() and live_worker == str(result['pid'])
                adb('shell', 'run-as', app, 'kill', '-9', live_worker)
                death = wait_for('worker DeathRecipient', lambda: value if (value := report(client)).get('binderDeathRun') == token else None)
                assert death['binderDead'] == 'true' and death['binderDeadCall'] == 'true', death
                wait_for('worker disconnection', lambda: report(client).get('binderDisconnected') == token)
            else:
                tap('Unbind service')
                wait_for('worker Service destroyed', lambda: worker_report(app).get('binderDestroyed') == result['instance'])
                if number == 1:
                    assert not report(client).get('binderDisconnected'), 'Unbind is not disconnection'
                    tap('Bind service')
            previous, previous_worker = result, snapshot
    except Exception:
        print(adb('logcat', '-d', '-t', '150', 'AndroidRuntime:E', 'ParavoidAndroid:D', '*:S', check=False), flush=True)
        raise
    finally:
        try:
            cleanup(app)
        finally:
            adb('shell', 'pm', 'default-state', boot_receiver)


if __name__ == '__main__':
    if not os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'):
        raise SystemExit('Set ANDROID_SERIAL to a dedicated unlocked emulator')
    prepare()
    for packaging in ('normal', 'paravoidAndroid'):
        for caller in ('peer', 'main'):
            check_mode(packaging, caller)
