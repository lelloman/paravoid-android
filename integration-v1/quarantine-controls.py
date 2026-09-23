#!/usr/bin/env python3
"""Exercise real quarantine confirmation and explicit cold restart on a disposable AVD.
Build the complete fixture with generation=broken, payloadVersion=2, bootstrap=embedded.
The fault is in the fixture Application, never in the production verifier/lifecycle.
"""
import argparse
from contextlib import ExitStack
import importlib.util
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('device_check', ROOT / 'compatibility/complete-v1/device-check.py')
device_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device_check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--corrupt-during-confirmation', action='store_true',
                        help='Corrupt selected DEX after opening the retry dialog; require safe refusal')
    parser.add_argument('--live-worker', action='store_true',
                        help='Use broken-main fixture; keep a real worker lease alive during retry confirmation')
    parser.add_argument('--repair', type=Path, help='Forward p3 repair output directory; stage while the retry dialog is open')
    parser.add_argument('--activate-repair', action='store_true', help='Cold-start worker on p3 before confirming the stale p2 dialog')
    parser.add_argument('--server-port', type=int, default=18765)
    args = parser.parse_args()
    if sum((args.live_worker, args.corrupt_during_confirmation, args.repair is not None)) > 1:
        parser.error('worker, corruption and repair are separate cases')
    if args.activate_repair and args.repair is None:
        parser.error('--activate-repair requires --repair')
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('dedicated disposable emulator required')
    with ExitStack() as stack:
        run(args, stack)


def run(args, stack):
    d = device_check.Device(args.serial, args.avd)
    app = device_check.SHELL
    device_check.check_shell(args.apk, 'embedded')
    d.run('uninstall', app, check=False)
    assert 'Success' in d.run('install', str(args.apk.resolve()))
    if args.repair:
        from recovery_repair_server import RepairServer
        server = RepairServer(d, args.apk, args.repair, args.server_port)
        stack.callback(server.close)
    if args.live_worker:
        assert 'generation=broken-main' in d.run('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
        worker_pid = d.run('shell', 'pidof', app + ':worker').strip()
        assert worker_pid.isdecimal()
    d.failed_application()
    initial = d.state()
    assert initial['quarantined'] and initial['active']['version'] == 2
    assert initial['healthy'] is None and initial['pending'] is None
    recovery_pid = d.run('shell', 'pidof', app + ':paravoid_recovery').strip()

    def ui():
        d.run('shell', 'uiautomator', 'dump', '/sdcard/paravoid-quarantine-ui.xml')
        return d.run('shell', 'cat', '/sdcard/paravoid-quarantine-ui.xml')

    def tap(label):
        for attempt in range(4):
            matches = [n for n in ET.fromstring(ui()).iter('node')
                       if n.attrib.get('text', '').lower() == label.lower()]
            if matches:
                a, b, c, e = map(int, re.findall(r'\d+', matches[0].attrib['bounds']))
                if c > a and e > b:
                    d.run('shell', 'input', 'tap', str((a+c)//2), str((b+e)//2))
                    return
            d.run('shell', 'input', 'swipe', '540', '1500', '540', '450', '300')
        raise AssertionError('Missing control: ' + label)

    tap('Retry this quarantined generation…')
    assert 'Retry p2 (payload 2)' in ui()
    tap('Cancel')
    assert d.state() == initial, 'Cancelling retry must preserve the journal'
    print('PASS: cancelling quarantine confirmation leaves selection unchanged', flush=True)
    tap('Retry this quarantined generation…')
    if args.repair:
        server.allowed.set()
        d.await_(lambda: d.state()['pending'] is not None, 'signed forward repair staged while dialog is open')
        assert d.state()['pending']['version'] == 3
        if args.activate_repair:
            assert 'generation=B' in d.run('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
            assert d.state()['active']['version'] == 3 and d.state()['pending'] is None
        before = d.state()
        security = d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True)
        tap('Retry this generation')
        d.await_(lambda: 'Update status: UNAVAILABLE' in ui(), 'stale or superseded quarantine retry refusal')
        assert d.state() == before, 'Superseded retry altered forward repair selection'
        assert d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True) == security
        assert d.run('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery_pid
        d.recovery()
        print('PASS: ' + ('stale identity' if args.activate_repair else 'pending forward repair') +
              ' refuses old dialog retry; forward selection/security preserved and recovery payload-free', args.serial, flush=True)
        d.run('shell', 'input', 'swipe', '540', '450', '540', '1500', '300')
        tap('Restart app…')
        tap('Stop and restart')
        d.healthy(3)
        assert d.state()['active']['version'] == 3 and not d.state()['quarantined']
        print('PASS: confirmed restart executes and marks forward repair healthy after refused retry', args.serial, flush=True)
        return
    if args.live_worker:
        security = d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True)
        tap('Retry this generation')
        d.await_(lambda: 'Update status: UNAVAILABLE' in ui(), 'live lease refusal after confirmation')
        assert d.state() == initial, 'Live-lease retry altered selection/quarantine'
        assert d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True) == security
        assert d.run('shell', 'pidof', app + ':worker').strip() == worker_pid
        assert d.run('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery_pid
        assert 'generation=broken-main' in d.run('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
        d.recovery()
        print('PASS: live worker lease refuses confirmed retry; quarantine/security unchanged, existing worker and payload-free recovery survive', args.serial, flush=True)
        return
    if args.corrupt_during_confirmation:
        security = d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True)
        component = 'no_backup/paravoid-v1/generations/' + initial['active']['directory'] + '/components/code/classes.dex'
        d.corrupt(component)
        tap('Retry this generation')
        d.await_(lambda: 'Update status: INTEGRITY' in ui(), 'corruption refusal after confirmation')
        assert d.state() == initial, 'Corrupt retry altered selection/quarantine'
        assert d.run('exec-out', 'run-as', app, 'cat', 'no_backup/paravoid-v1/security', binary=True) == security
        assert d.run('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery_pid
        d.recovery()
        print('PASS: selected bytes corrupted after dialog; exact retry refused, quarantine/security preserved, recovery stays payload-free', args.serial, flush=True)
        return
    tap('Retry this generation')
    d.await_(lambda: not d.state()['quarantined'], 'confirmed quarantine retry')
    retry = d.state()
    assert retry['trial'] and retry['active'] == initial['active']
    assert retry['healthy'] is None and retry['pending'] is None
    print('PASS: confirmation retries the exact selected identity, without rollback', flush=True)

    # Scroll back to the shell restart control; no force-stop is used for activation.
    d.run('shell', 'input', 'swipe', '540', '450', '540', '1500', '300')
    tap('Restart app…')
    tap('Stop and restart')
    d.await_(lambda: d.state()['quarantined'], 'repeated real Application failure')
    d.await_(lambda: not d.run('shell', 'pidof', app, check=False).strip(), 'failed retry process exits')
    assert d.run('shell', 'pidof', app + ':paravoid_recovery').strip() == recovery_pid
    assert d.state()['active'] == initial['active'] and d.state()['healthy'] is None
    d.launch()  # Ordinary user re-entry after the crashing Application; no forced death.
    d.recovery()
    print('PASS: confirmed restart actually retries payload; repeated failure re-quarantines and recovery remains payload-free', flush=True)
    print('Device:', args.serial, 'API', d.run('shell', 'getprop', 'ro.build.version.sdk').strip(), flush=True)


if __name__ == '__main__':
    main()
