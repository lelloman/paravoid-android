#!/usr/bin/env python3
"""AndroidX ActivityResultRegistry success/cancel, with and without caller death."""
import json
import time
import uuid
from integration_driver import adb, report, PEER, wait_for, tap, kill_target, install, launch, prepare, cleanup


def check_case(mode, cold, cancel):
    app = install(mode)
    token = uuid.uuid4().hex
    try:
        launch(app, mode, 'result', token, '--ez', 'cancel', str(cancel).lower())
        peer = wait_for('peer holds result', lambda: value if (value := report(PEER)).get('resultReady') == token else None)
        original = wait_for('caller saved state', lambda: value if (value := report(app)).get('resultSavedRun') == token else None)
        old_pid = original['activityPid']
        assert original['resultSavedPid'] == old_pid
        assert original['resultLaunches'] == '1'
        if cold:
            # Let the state-save transaction finish before killing the stopped caller.
            time.sleep(1)
            kill_target(app, old_pid)
        assert adb('shell', 'pidof', PEER) == peer['resultPeerPid'], 'Peer was restarted'
        tap('Return activity result')

        def delivered():
            prefs = report(app)
            assert not prefs.get('resultError'), prefs
            value = json.loads(prefs.get('activityResult', '{}'))
            return value if value.get('run') == token else None
        result = wait_for('AndroidX callback', delivered)
        assert result['restored'] is cold, result
        assert (str(result['pid']) != old_pid) is cold, result
        assert str(result['pid']) == report(app)['startupPid'] == report(app)['activityPid'], result
        assert result['callbacks'] == 1 and result['launches'] == 1, result
        assert all(result[key] is True for key in ('savedParcel', 'payloadLoader', 'code', 'data')), result
        time.sleep(1)
        assert report(app)['resultCallbacks'] == '1' and report(app)['resultLaunches'] == '1'
        print(f'PASS {mode} {"process-death" if cold else "alive"} {"cancel" if cancel else "success"}: registry callback once, no relaunch, correct result, payload saved state/loader', flush=True)
    finally:
        cleanup(app)


if __name__ == '__main__':
    prepare()
    for packaging in ('normal', 'paravoidAndroid'):
        for killed in (False, True):
            for canceled in (False, True):
                check_case(packaging, killed, canceled)
