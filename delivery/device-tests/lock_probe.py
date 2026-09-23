#!/usr/bin/env python3
"""ART/file-lock primitive gate, not installed-app or HTTP acceptance."""
import argparse
import os
from pathlib import Path
import re
import selectors
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True, help='Expected disposable AVD name')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('explicit disposable emulator required')
    def run(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=45).strip()
    def adb(*parts):
        return run(['adb', '-s', args.serial, *parts])
    assert adb('emu', 'avd', 'name').splitlines()[0] == args.avd
    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    sdk = Path(os.environ.get('ANDROID_HOME', '/home/lelloman/Android/Sdk'))
    package = 'com/lelloman/paravoidandroid/delivery'
    with tempfile.TemporaryDirectory(prefix='paravoid-delivery-locks-') as temporary:
        output = Path(temporary)
        classes = output / 'classes'
        classes.mkdir()
        run(['javac', '--release', '11', '-d', str(classes),
             str(ROOT / 'delivery/src' / package / 'DeliveryLocks.java'),
             str(ROOT / 'delivery/test' / package / 'DeliveryLockProbe.java')])
        run(['jar', 'cf', str(output / 'classes.jar'), '-C', str(classes), '.'])
        run([str(sdk / 'build-tools/36.0.0/d8'), '--min-api', '30', '--lib',
             str(sdk / 'platforms/android-36/android.jar'), '--output', str(output / 'probe.zip'),
             str(output / 'classes.jar')])
        remote = '/data/local/tmp/' + output.name
        adb('shell', 'mkdir', remote)
        owners = []
        try:
            adb('push', str(output / 'probe.zip'), remote + '/probe.zip')
            adb('shell', 'chmod', '444', remote + '/probe.zip')
            command = ['adb', '-s', args.serial, 'shell', 'dalvikvm', '-cp', remote + '/probe.zip',
                       package.replace('/', '.') + '.DeliveryLockProbe']
            for name in ('preferences.attempt', 'transfer.lock'):
                path = remote + '/' + name
                for kill in (False, True):
                    adb('shell', 'rm', '-f', remote + '/release')
                    owner = subprocess.Popen(command + ['hold', path], text=True,
                                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    owners.append([owner, None])
                    with selectors.DefaultSelector() as ready:
                        ready.register(owner.stdout, selectors.EVENT_READ)
                        assert ready.select(timeout=20), 'owner handshake timed out'
                        line = owner.stdout.readline().strip()
                    assert re.fullmatch(r'HELD \d+', line), line
                    owners[-1][1] = line.split()[1]
                    assert run(command + ['probe', path]) == 'BUSY'
                    if kill:
                        adb('shell', 'kill', '-9', owners[-1][1])
                    else:
                        adb('shell', 'touch', remote + '/release')
                    owner.wait(timeout=10)
                    if not kill:
                        assert owner.returncode == 0
                    assert run(command + ['probe', path]) == 'FREE'
                    print('PASS ART:', name, 'same-VM contention preserves OS exclusion;',
                          'death' if kill else 'close', 'releases ownership', flush=True)
            print('Device:', args.serial, adb('shell', 'getprop', 'ro.build.fingerprint'))
        finally:
            for owner, pid in owners:
                if owner.poll() is None:
                    if pid:
                        subprocess.run(['adb', '-s', args.serial, 'shell', 'kill', '-9', pid], timeout=10,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    owner.terminate()
                    owner.wait(timeout=10)
                owner.stdout.close()
            adb('shell', 'rm', '-rf', remote)  # Only this run's fresh emulator scratch directory.


if __name__ == '__main__':
    main()
