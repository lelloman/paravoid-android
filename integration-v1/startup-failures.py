#!/usr/bin/env python3
"""Real component startup failures; requires matching startupFault fixture build."""
import argparse
import importlib.util
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('device_check', ROOT / 'compatibility/complete-v1/device-check.py')
dcheck = importlib.util.module_from_spec(spec); spec.loader.exec_module(dcheck)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--avd', required=True)
parser.add_argument('--fault', required=True, choices=(
    'provider-constructor', 'provider-create', 'activity-constructor', 'activity-create',
    'service-constructor', 'service-create', 'none'))
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), 'Disposable emulator required'
d = dcheck.Device(args.serial, args.avd)
output = ROOT / 'compatibility/complete-v1/build/outputs'
apk = output / 'paravoid/paravoidAndroidDebug/shell.apk'
dcheck.check_shell(apk, 'embedded')
normal = dcheck.PACKAGE
d.run('uninstall', normal, check=False)
assert 'Success' in d.run('install', output / 'apk/normal/debug/complete-v1-normal-debug.apk')
d.run('uninstall', dcheck.SHELL, check=False)
assert 'Success' in d.run('install', apk)
service = args.fault.startswith('service-')

def start(package):
    if service:
        d.run('shell', 'cmd', 'deviceidle', 'tempwhitelist', '-d', '60000', package)
        d.run('shell', 'am', 'startservice', '-n', package + '/' + normal + '.ProbeService', check=False)
    else:
        d.run('shell', 'am', 'start', '-n', package + '/' +
              (dcheck.LAUNCHER if package == dcheck.SHELL else normal + '.MainActivity'), check=False)

if args.fault == 'none':
    d.launch(normal); d.await_(lambda: d.markers(normal).get('activity', '').startswith('generation=A;'), 'normal control')
    d.launch(); d.healthy(1)
    before = d.state()
    previous_pid = d.run('shell', 'pidof', dcheck.SHELL).strip()
    assert previous_pid.isdecimal()
    d.run('logcat', '-c')
    d.run('shell', 'am', 'start', '-n', dcheck.SHELL + '/' + normal + '.MainActivity', '--ez', 'crashCreate', 'true', check=False)
    d.await_(lambda: 'Later Activity creation fixture' in d.run('logcat', '-d', '-t', '2000'), 'later failure reached')
    d.await_(lambda: d.run('shell', 'pidof', dcheck.SHELL, check=False).strip() != previous_pid, 'later crash exits old process')
    assert d.state() == before, 'Later Activity creation failure quarantined healthy process'
    d.launch(); d.healthy(1)
    print('PASS later Activity creation crash does not quarantine; normal and shell healthy launch', args.serial, flush=True)
else:
    d.run('logcat', '-c')
    start(normal)
    d.await_(lambda: 'Startup fixture: ' + args.fault in d.run('logcat', '-d', '-t', '2000'), 'normal injected failure')
    d.await_(lambda: not d.run('shell', 'pidof', normal, check=False).strip(), 'normal crash exits')
    start(dcheck.SHELL)
    d.await_(lambda: d.run('shell', 'run-as', dcheck.SHELL, 'ls', 'no_backup/paravoid-v1/selection', check=False)
             .strip().endswith('/selection'), 'shell journal')
    d.await_(lambda: d.state()['quarantined'], 'component startup quarantine')
    state = d.state()
    assert state['active']['version'] == 1 and state['healthy'] is None
    assert state['pending'] is None
    d.await_(lambda: not d.run('shell', 'pidof', dcheck.SHELL, check=False).strip(), 'startup crash exits')
    d.launch(); d.recovery()
    assert d.state()['quarantined'] and d.state()['active'] == state['active']
    print('PASS normal crash / shell quarantine and payload-free recovery:', args.fault, args.serial, flush=True)
