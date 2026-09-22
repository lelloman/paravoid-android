#!/usr/bin/env python3
"""Exercise real unavailable component kinds, using a separate installed client."""
import argparse
import importlib.util
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixture', ROOT / 'device-check.py')
f = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)


def output(d, *args):
    result = subprocess.run(d.adb + list(args), text=True, capture_output=True, timeout=30)
    return result.stdout + result.stderr


def unavailable(d):
    before = d.markers()
    query = output(d, 'shell', 'content', 'query', '--uri', 'content://' + f.SHELL + '.probe')
    assert 'Paravoid payload unavailable' in query and 'Row:' not in query, query
    private = output(d, 'shell', 'content', 'query', '--uri', 'content://' + f.SHELL + '.private')
    assert 'Permission Denial' in private and 'not exported' in private, private
    d.run('shell', 'am', 'broadcast', '-n', f.SHELL + '/' + f.PACKAGE + '.ProbeReceiver')
    d.run('shell', 'cmd', 'deviceidle', 'tempwhitelist', '-d', '60000', f.SHELL)
    d.run('shell', 'am', 'startservice', '-n', f.SHELL + '/' + f.PACKAGE + '.ProbeService')
    # A real foreground-service start must satisfy Android's timeout behavior.
    d.run('shell', 'am', 'start-foreground-service', '-n', f.SHELL + '/' + f.PACKAGE + '.ProbeService$Foreground')
    d.run('shell', 'am', 'broadcast', '-a', 'probe.bind', '-n', f.PACKAGE + '/' + f.PACKAGE + '.ProbeReceiver')
    d.await_(lambda: d.markers(f.PACKAGE).get('binding') == 'unavailable', 'external client onNullBinding')
    time.sleep(12)
    assert d.markers() == before, 'Unavailable inputs executed payload code'
    services = d.run('shell', 'dumpsys', 'activity', 'services', f.SHELL)
    assert 'startRequested=true' not in services and 'fgRequired=true' not in services, services
    pid = d.run('shell', 'pidof', f.SHELL).strip()
    assert pid.isdecimal(), 'Unavailable service process crashed'
    d.launch()
    d.recovery()
    print('PASS explicit provider failure, non-exported provider enforcement, receiver, started/foreground/null-bound service, Activity recovery', flush=True)


def test(d):
    d.install('normal', package=f.PACKAGE)
    d.launch(f.PACKAGE)
    d.install('empty')
    d.launch()
    unavailable(d)
    d.install('broken')
    d.failed_application()
    assert d.state()['quarantined']
    unavailable(d)
    d.install('B')
    d.launch()
    d.healthy(3)
    d.install('A', fresh=False)
    d.launch(schedule=True)
    d.run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    # Kill the main process without force-stop: the actual scheduled Job survives.
    pid = d.run('shell', 'pidof', f.SHELL).strip()
    assert pid.isdecimal()
    d.run('shell', 'am', 'broadcast', '-a', 'probe.die', '-n',
          f.SHELL + '/' + f.PACKAGE + '.ProbeReceiver', check=False)
    d.await_(lambda: not d.run('shell', 'pidof', f.SHELL, check=False).strip(), 'scheduled app process death')
    d.corrupt('no_backup/paravoid-v1/generations/' + d.state()['active']['directory'] + '/components/resources.apk')
    result = output(d, 'shell', 'cmd', 'jobscheduler', 'run', '-f', f.SHELL, '123')
    assert 'Running job' in result, result
    d.await_(lambda: d.state()['quarantined'], 'JobService cold startup rejects corrupt bytes')
    def rescheduled():
        dump = d.run('shell', 'dumpsys', 'jobscheduler', f.SHELL)
        (f.ARTIFACTS / ('jobs-' + d.adb[-1] + '.txt')).write_text(dump)
        return 'Num failures: 1' in dump or 'numFailures=1' in dump
    d.await_(rescheduled, 'actual JobService finished with rescheduling')
    assert 'job' not in d.markers()
    print('PASS actual declared JobService receives scheduled work and requests reschedule while corrupt payload is unavailable', flush=True)
    unavailable(d)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--avd', required=True)
    args = p.parse_args()
    test(f.Device(args.serial, args.avd))
