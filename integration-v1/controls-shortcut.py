#!/usr/bin/env python3
"""After public_bootstrap.py: verify runnable-app controls through Pixel launcher UI.
Only a dedicated disposable emulator is accepted; no install or payload mutation.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--restart-worker', action='store_true', help='Confirm restart with a live payload worker service')
parser.add_argument('--sticky-worker', action='store_true', help='Exercise Android foreground sticky-worker respawn; requires --restart-worker')
parser.add_argument('--shared-uid', action='store_true', help='Require shared-UID build; verify dormant/live peer rejection')
parser.add_argument('--restart-timeout', action='store_true', help='Debugger-scheduled real worker forces restart polling timeout')
parser.add_argument('--pending-update', type=Path, help='Immutable generation B/version 2 output directory; requires --restart-worker')
parser.add_argument('--journal-death', choices=('content-synced', 'renamed', 'directory-synced'),
                    help='Terminate the installed recovery process at this pending-selection write boundary')
parser.add_argument('--head-revision', type=int, default=2,
                    help='Monotonic signed head revision for repeated B delivery attempts')
parser.add_argument('--server-port', type=int, default=18765)
args = parser.parse_args()
if args.restart_timeout and (not args.restart_worker or args.shared_uid or args.sticky_worker or args.pending_update):
    parser.error('--restart-timeout requires --restart-worker and no other variant')
if args.shared_uid and (not args.restart_worker or args.pending_update or args.sticky_worker):
    parser.error('--shared-uid requires --restart-worker and is separate from other variants')
if args.sticky_worker and not args.restart_worker:
    parser.error('--sticky-worker requires --restart-worker')
if args.pending_update and not args.restart_worker:
    parser.error('--pending-update requires --restart-worker')
if args.journal_death and not args.pending_update:
    parser.error('--journal-death requires --pending-update')
if args.head_revision < 2:
    parser.error('--head-revision must be at least 2')
if not 1 <= args.server_port <= 65535:
    parser.error('invalid host port')
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('dedicated emulator required')


def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.serial, *parts], text=True, timeout=45)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/paravoid-shortcut-test.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/paravoid-shortcut-test.xml')).iter('node'))


def point(node):
    a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return str((a + c) // 2), str((b + d) // 2)


app = 'com.lelloman.paravoidcompat.complete.paravoid'
assert 'generation=A;asset=payload-asset;java=payload-java-resource' in adb(
    'shell', 'run-as', app, 'cat', 'shared_prefs/probe.xml')
if args.restart_worker:
    if args.shared_uid:
        # The normal control APK is the same-signed peer. Force-stop before
        # starting our test processes because shared-UID package stopping may
        # also terminate them. Never do this during the restart assertions.
        adb('shell', 'am', 'force-stop', 'com.lelloman.paravoidcompat.complete')
    adb('shell', 'am', 'start', '-W', '-n', app + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')
    component = app + '/com.lelloman.paravoidcompat.complete.ProbeService$Worker'
    adb('shell', 'am', 'start-foreground-service' if args.sticky_worker else 'startservice',
        '-n', "'" + component + "'", '-a', 'sticky' if args.sticky_worker else 'hold')
    assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
    old_main = adb('shell', 'pidof', app).strip()
    old_worker = adb('shell', 'pidof', app + ':worker').strip()
    assert old_main and old_worker and old_main != old_worker
size = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
w, h = map(int, size.groups())


def drawer_icons():
    # HOME is asynchronous; swiping before the launcher transition settles can
    # leave us on the home screen instead of opening the drawer (especially API 36).
    for _ in range(3):
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(.75)
        adb('shell', 'input', 'swipe', str(w // 2), str(h * 9 // 10), str(w // 2), str(h // 4), '400')
        time.sleep(1)
        icons = [n for n in nodes() if n.attrib.get('text') == 'Paravoid complete fixture']
        if icons:
            return icons
    return []


icons = drawer_icons()
assert icons, 'Fixture icon not visible in launcher drawer'
opened = False
for index in range(len(icons)):
    # Back after a non-shortcut icon can close the whole drawer on newer launchers.
    # Reopen it and resolve fresh bounds instead of tapping stale coordinates.
    current_icons = drawer_icons()
    assert len(current_icons) > index, 'Fixture icon disappeared from drawer'
    icon = current_icons[index]
    x, y = point(icon)
    adb('shell', 'input', 'swipe', x, y, x, y, '900')
    time.sleep(1)
    shortcuts = [n for n in nodes() if n.attrib.get('text') in ('App updates', 'Paravoid app updates')]
    if shortcuts:
        adb('shell', 'input', 'tap', *point(shortcuts[0]))
        opened = True
        break
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
assert opened, 'Shell controls shortcut not found'
for _ in range(10):
    texts = '\n'.join(n.attrib.get('text', '') for n in nodes())
    if 'A local app generation is available.' in texts and 'check now' in texts.lower():
        break
    time.sleep(1)
else:
    raise AssertionError('Shortcut did not open runnable-app controls: ' + texts)
print('PASS: runnable payload -> launcher shortcut -> private shell controls', args.serial)
if args.restart_worker:
    def tap(label):
        node = next(n for n in nodes() if n.attrib.get('text', '').lower() == label.lower())
        adb('shell', 'input', 'tap', *point(node))
    if args.restart_timeout:
        sys.dont_write_bytecode = True
        from restart_timeout import run
        run(args, app, adb, nodes, tap, old_main, old_worker)
        sys.exit(0)
    if args.shared_uid:
        sys.dont_write_bytecode = True
        from shared_uid import run
        run(args, app, adb, nodes, tap, old_main, old_worker)
        sys.exit(0)
    if args.sticky_worker and not args.pending_update:
        sys.dont_write_bytecode = True
        from sticky_worker import run
        run(args, app, adb, nodes, tap, old_main, old_worker)
        sys.exit(0)
    if args.pending_update:
        sys.dont_write_bytecode = True
        from pending_worker import run
        run(args, app, adb, nodes, tap, old_main, old_worker)
        sys.exit(0)
    recovery = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
    tap('Restart app…')
    tap('Cancel')
    assert adb('shell', 'pidof', app).strip() == old_main
    assert adb('shell', 'pidof', app + ':worker').strip() == old_worker
    tap('Restart app…')
    tap('Stop and restart')
    for _ in range(30):
        try:
            new_main = adb('shell', 'pidof', app).strip()
            if new_main and new_main != old_main:
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(.5)
    else:
        raise AssertionError('Confirmed restart did not replace the main process')
    worker = subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', app + ':worker'],
                            capture_output=True, text=True, timeout=45)
    assert not worker.stdout.strip(), 'Non-sticky worker must be stopped, not retained across restart'
    assert adb('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery
    for _ in range(10):
        texts = '\n'.join(n.attrib.get('text', '') for n in nodes())
        if 'generation=A;asset=payload-asset;java=payload-java-resource' in texts:
            break
        time.sleep(.5)
    else:
        raise AssertionError('Fresh payload Activity not visible: ' + texts)
    print('PASS: cancel preserves both processes; confirmed restart stops leased worker and main, preserves recovery, and launches payload', args.serial)
