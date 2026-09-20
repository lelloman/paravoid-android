#!/usr/bin/env python3
"""Cold notification action/content delivery; no target instrumentation."""
import json
import uuid
from integration_driver import adb, report, PEER, wait_for, tap, kill_target, install, launch, prepare, cleanup


def snapshot(app, key, token):
    value = json.loads(report(app).get(key, '{}'))
    if 'error' in value:
        raise AssertionError(value)
    return value if value.get('run') == token else None


def check_mode(mode):
    app = install(mode)
    token = uuid.uuid4().hex[:12]
    try:
        # Permission UI belongs to a separate contract test; this scenario starts
        # with notifications enabled and does not claim runtime permission coverage.
        adb('shell', 'pm', 'grant', app, 'android.permission.POST_NOTIFICATIONS')
        launch(app, mode, 'notification', token)
        posted = wait_for('posted notification', lambda: snapshot(app, 'posted', token))
        assert all(posted[key] is True for key in ('enabled', 'channel', 'contentToken', 'actionToken')), posted
        peer = wait_for('peer token ready', lambda: value if (value := report(PEER)).get('ready') == token else None)
        assert peer['creator'] == app and int(peer['creatorUid']) > 0 and peer['creatorUid'] != peer['uid'], peer
        old_pid = report(app)['activityPid']
        kill_target(app, old_pid)
        tap('Send pending action')
        action = wait_for('cold action receiver', lambda: snapshot(app, 'action', token))
        assert all(action[key] is True for key in ('parcel', 'loader', 'immutable')), action
        assert str(action['pid']) != old_pid and str(action['pid']) == report(app)['startupPid'], action
        assert report(app)['activityPid'] == old_pid, 'Receiver unexpectedly entered user Activity'
        kill_target(app, action['pid'])
        adb('shell', 'cmd', 'statusbar', 'expand-notifications')
        tap('Paravoid ' + token)
        content = wait_for('cold notification Activity', lambda: snapshot(app, 'content', token))
        assert all(content[key] is True for key in ('parcel', 'loader', 'immutable')), content
        assert str(content['pid']) not in (old_pid, str(action['pid'])), content
        assert str(content['pid']) == report(app)['activityPid'] == report(app)['startupPid'], content
        print(f'PASS {mode}: posted channel/tokens, cross-UID immutable action, cold receiver and notification-shade Activity, payload Parcelables', flush=True)
    finally:
        adb('shell', 'cmd', 'statusbar', 'collapse', check=False)
        cleanup(app)


if __name__ == '__main__':
    prepare()
    for packaging in ('normal', 'paravoidAndroid'):
        check_mode(packaging)
