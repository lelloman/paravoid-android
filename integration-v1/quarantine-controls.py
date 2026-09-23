#!/usr/bin/env python3
"""Exercise real quarantine confirmation and explicit cold restart on a disposable AVD.
Build the complete fixture with generation=broken, payloadVersion=2, bootstrap=embedded.
The fault is in the fixture Application, never in the production verifier/lifecycle.
"""
import argparse
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
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('dedicated disposable emulator required')
    d = device_check.Device(args.serial, args.avd)
    app = device_check.SHELL
    device_check.check_shell(args.apk, 'embedded')
    d.run('uninstall', app, check=False)
    assert 'Success' in d.run('install', str(args.apk.resolve()))
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
