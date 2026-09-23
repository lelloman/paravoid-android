#!/usr/bin/env python3
"""Test shell launcher fallback on an explicitly owned disposable emulator.

Uses the ordinary embedded complete-v1 debug fixture; replaces ONLY that fixture.
No HTTP server, payload UI integration, or dynamic shortcut is needed to enter.
"""
import argparse
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('device_check', ROOT / 'compatibility/complete-v1/device-check.py')
device_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device_check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    args = parser.parse_args()
    d = device_check.Device(args.serial, args.avd)
    app = device_check.SHELL
    alias = app + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'
    device_check.check_shell(args.apk, 'embedded')
    d.run('uninstall', app, check=False)
    assert 'Success' in d.run('install', str(args.apk.resolve()))
    launchers = d.run('shell', 'cmd', 'package', 'query-activities', '--brief',
                     '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', app)
    assert alias in launchers and device_check.LAUNCHER in launchers, launchers
    # First-ever entry: the app has never run to register a dynamic shortcut.
    d.run('shell', 'am', 'start', '-W', '-n', alias, '-a', 'paravoid.RESTART',
          '-d', 'paravoid://retry', '--ez', 'restart', 'true', '--ez', 'retry', 'true')
    d.recovery()
    assert not d.run('shell', 'pidof', app, check=False).strip(), 'Fallback started the payload process'
    assert not d.markers(), 'Fallback ran payload initialization'
    d.run('shell', 'uiautomator', 'dump', '/sdcard/paravoid-controls-ui.xml')
    assert 'check now' in d.run('shell', 'cat', '/sdcard/paravoid-controls-ui.xml').lower()
    # Existing healthy payload: removing shortcuts does not remove the manifest entry.
    d.launch(); d.healthy(1)
    before = d.state()
    d.stop()
    d.run('shell', 'cmd', 'shortcut', 'clear-shortcuts', app)
    d.run('shell', 'am', 'start', '-W', '-n', alias, '--ez', 'restart', 'true')
    d.recovery()
    assert d.state() == before, 'Opening public controls changed selection'
    assert not d.run('shell', 'pidof', app, check=False).strip(), 'Opening controls restarted payload'
    print('PASS manifest launcher: first-ever cold entry, healthy offline entry after shortcut removal, payload-free recovery, extras inert')
    d.stop()


if __name__ == '__main__':
    main()
