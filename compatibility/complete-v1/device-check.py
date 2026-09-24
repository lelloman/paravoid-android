#!/usr/bin/env python3
"""Installed-app acceptance on an explicitly named disposable emulator.

Builds genuine complete VPKs with the production plugin; never supplies a verifier
or loader substitute. Fault injection mutates only this fixture's private data.
"""
import argparse
import base64
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
PACKAGE = 'com.lelloman.paravoidcompat.complete'
SHELL = PACKAGE + '.paravoid'
LAUNCHER = 'com.lelloman.paravoidandroid.runtime.LauncherActivity'
ARTIFACTS = ROOT / 'build/device-cases'


def check_shell(path, bootstrap):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert 'assets/paravoid/shell-policy.json' in names
        assert ('assets/paravoid/payload.vpk' in names) == (bootstrap == 'embedded')
        assert 'assets/probe.txt' not in names and 'fixture.txt' not in names
        for name in names:
            if not name.startswith('lib/'):
                continue
            _, abi, library = name.split('/')
            assert library == 'libparavoid_abi.so', 'Movable native fallback in shell'
            marker = REPO / 'paravoid-gradle-plugin/src/main/resources/com/lelloman/paravoid/abi' / (abi + '.base64')
            assert apk.read(name) == base64.b64decode(marker.read_bytes()), 'Unexpected shell ABI marker'


def command(args, *, binary=False, check=True, timeout=180, **kwargs):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=not binary,
                            timeout=timeout, **kwargs)
    if check and result.returncode:
        raise AssertionError(f"Command failed: {args}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def build(minify=False):
    command(['python3', ROOT / 'prepare.py'])
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    for generation, version, bootstrap in [('A', 1, 'embedded'), ('broken', 2, 'embedded'),
                                           ('B', 3, 'embedded'), ('incomplete', 4, 'embedded'),
                                           ('empty', 1, 'empty')]:
        args = [REPO / 'gradlew', '-p', ROOT, 'assembleNormalDebug', 'assembleParavoidAndroidDebug',
                '--offline', '--no-daemon', '--max-workers=2', f'-Pgeneration={generation}',
                f'-PpayloadVersion={version}', f'-Pbootstrap={bootstrap}']
        if minify:
            args.append('-PminifyPayload=true')
        if generation not in ('A', 'empty'):
            args.append('-Pbaseline')
        output = command(args, cwd=REPO, timeout=600)
        (ARTIFACTS / f'{generation}-build.log').write_text(output)
        source = ROOT / 'build/outputs/paravoid/paravoidAndroidDebug'
        if minify:
            mapping = source / 'payload-mapping.txt'
            text = mapping.read_text()
            assert text.startswith('# compiler: R8\n')
            assert re.search(r'^com\.lelloman\.paravoidcompat\.complete\.StartupProbe -> '
                             r'(?!com\.lelloman\.paravoidcompat\.complete\.StartupProbe:)[^:]+:$',
                             text, re.MULTILINE), 'R8 did not obfuscate the payload probe'
            shutil.copyfile(mapping, ARTIFACTS / f'{generation}-mapping.txt')
        shutil.copyfile(source / 'shell.apk', ARTIFACTS / f'{generation}.apk')
        check_shell(ARTIFACTS / f'{generation}.apk', bootstrap)
        if bootstrap == 'embedded':
            shutil.copyfile(source / 'payload.vpk', ARTIFACTS / f'{generation}.vpk')
        if generation == 'A':
            shutil.copyfile(ROOT / 'build/outputs/apk/normal/debug/complete-v1-normal-debug.apk', ARTIFACTS / 'normal.apk')
            # Fixture-only promotion: subsequent variants must satisfy the actual
            # fixed installed baseline, not merely happen to allocate identical IDs.
            shutil.copytree(source / 'baseline-candidate', ROOT / 'build/accepted/paravoidAndroidDebug', dirs_exist_ok=True)
        print(f'BUILT {generation}', flush=True)


class Record:
    """Read-only test decoder for C's private checksummed journal, not authority."""
    def __init__(self, data):
        assert data[:8] == struct.pack('>II', 0x50564c31, 1)
        assert len(data) == 44 + struct.unpack('>I', data[8:12])[0]
        assert hashlib.sha256(data[:-32]).digest() == data[-32:]
        self.data = memoryview(data)[12:-32]
        self.offset = 0

    def number(self, fmt):
        value = struct.unpack_from('>' + fmt, self.data, self.offset)[0]
        self.offset += struct.calcsize('>' + fmt)
        return value

    def string(self):
        size = self.number('H')
        value = bytes(self.data[self.offset:self.offset + size]).decode('ascii')
        self.offset += size
        return value

    def generation(self):
        if not self.number('?'):
            return None
        return dict(directory=self.string(), release=self.string(), version=self.number('q'),
                    manifest=self.string(), archive=self.string(), size=self.number('q'))

    def selection(self):
        assert self.number('i') == 2
        state = dict(active=self.generation(), pending=self.generation(), healthy=self.generation(),
                     trial=self.number('?'), quarantined=self.number('?'), failure=self.string(),
                     incomplete=self.number('i'), retained=self.number('i'))
        return state


class Device:
    def __init__(self, serial, avd):
        assert serial.startswith('emulator-'), 'Physical devices are intentionally unsupported'
        self.adb = ['adb', '-s', serial]
        assert self.run('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        assert self.run('emu', 'avd', 'name').splitlines()[0] == avd, 'AVD identity mismatch'
        # Let Android terminate deliberately crashing fixture processes without
        # waiting for a human to dismiss its crash dialog on this disposable AVD.
        self.run('shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1')
        self.run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        self.run('shell', 'wm', 'dismiss-keyguard')

    def run(self, *args, **kwargs):
        return command(self.adb + list(args), **kwargs)

    def state(self):
        return Record(self.run('exec-out', 'run-as', SHELL, 'cat', 'no_backup/paravoid-v1/selection', binary=True)).selection()

    def markers(self, package=SHELL):
        xml = self.run('exec-out', 'run-as', package, 'cat', 'shared_prefs/probe.xml', check=False)
        if not xml.startswith('<?xml'):
            return {}
        return {e.attrib['name']: e.text for e in ET.fromstring(xml)}

    def install(self, variant, fresh=True, package=SHELL):
        if fresh:
            self.run('uninstall', package, check=False)
        assert 'Success' in self.run('install', '-r', ARTIFACTS / f'{variant}.apk')
        # Explicit test scheduling allowance; keeps targetSdk and platform component
        # security unchanged while allowing adb to trigger a background-only start.
        self.run('shell', 'cmd', 'deviceidle', 'tempwhitelist', '-d', '60000', package)

    def launch(self, package=SHELL, wait=True, **extras):
        component = LAUNCHER if package == SHELL else PACKAGE + '.MainActivity'
        args = ['shell', 'am', 'start', '-n', package + '/' + component]
        if wait:
            args.append('-W')
        for key, value in extras.items():
            args.extend(['--ez', key, str(value).lower()])
        return self.run(*args)

    def failed_application(self):
        self.launch(wait=False)
        self.await_(lambda: self.run('shell', 'run-as', SHELL, 'ls',
                    'no_backup/paravoid-v1/selection', check=False).strip().endswith('/selection'), 'initial journal')
        self.await_(lambda: self.state()['quarantined'], 'Application startup quarantine')
        self.await_(lambda: not self.run('shell', 'pidof', SHELL, check=False).strip(), 'failed Application process exits')
        self.launch()
        self.recovery()

    def stop(self):
        self.run('shell', 'am', 'force-stop', SHELL)

    def await_(self, predicate, label):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.25)
        raise AssertionError('Timed out: ' + label)

    def healthy(self, version):
        self.await_(lambda: self.state()['healthy'] is not None and self.state()['healthy']['version'] == version,
                    f'healthy generation {version}')

    def native_generation(self):
        pid = self.run('shell', 'pidof', SHELL).strip()
        assert pid.isdecimal()
        maps = self.run('shell', 'run-as', SHELL, 'cat', f'/proc/{pid}/maps')
        libraries = [line for line in maps.splitlines() if 'libprobe_' in line or 'libc++_shared.so' in line]
        assert libraries and any('libprobe_dep.so' in line for line in libraries)
        root = '/generations/' + self.state()['active']['directory'] + '/components/native/'
        assert all(root in line for line in libraries), libraries

    def recovery(self):
        self.await_(lambda: 'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity' in
                    self.run('shell', 'dumpsys', 'activity', 'activities'), 'recovery Activity')
        pid = self.run('shell', 'pidof', SHELL + ':paravoid_recovery').strip()
        assert pid.isdecimal()
        descriptors = self.run('shell', 'run-as', SHELL, 'ls', '-l', f'/proc/{pid}/fd')
        mappings = self.run('shell', 'run-as', SHELL, 'cat', f'/proc/{pid}/maps')
        assert '/lease-' not in descriptors and '/generations/' not in descriptors
        assert '/generations/' not in mappings

    def corrupt(self, path):
        # The caller selected our disposable emulator and fixture package explicitly.
        # Shell syntax is passed as one remote command, with a generated safe path.
        assert all(c.isalnum() or c in '/._-' for c in path)
        self.run('shell', f'run-as {SHELL} sh -c "chmod u+w {path}; echo corrupt > {path}"')


def test(d):
    d.install('normal', package=PACKAGE)
    d.launch(PACKAGE)
    d.await_(lambda: d.markers(PACKAGE).get('activity', '').startswith('generation=A;'), 'normal control')
    print('PASS normal packaging control', flush=True)

    d.install('A')
    d.launch()
    d.healthy(1)
    assert d.markers()['activity'] == 'generation=A;asset=payload-asset;java=payload-java-resource'
    first = d.state()['active']
    d.native_generation()
    d.stop()
    d.launch()
    d.healthy(1)
    assert d.state()['active'] == first
    assert 'generation=A' in d.run('shell', 'content', 'query', '--uri', 'content://' + SHELL + '.probe')
    print('PASS complete embedded startup, configuration context, provider order, first frame, offline cold reuse', flush=True)

    d.install('A')
    d.run('shell', 'am', 'startservice', '-n', SHELL + '/' + PACKAGE + '.ProbeService')
    d.await_(lambda: d.markers().get('service') == 'A', 'background service')
    assert d.state()['trial'] and d.state()['healthy'] is None and d.state()['incomplete'] == 0
    d.run('shell', 'am', 'startservice', '-n', SHELL + '/' + PACKAGE + '.ProbeService$Worker', '-a', 'hold')
    assert 'generation=A' in d.run('shell', 'content', 'query', '--uri', 'content://' + SHELL + '.worker')
    assert d.state()['trial'] and d.state()['incomplete'] == 0
    d.launch()
    d.healthy(1)
    print('PASS background remains trial; named worker joins coherent generation; UI establishes health', flush=True)

    d.install('empty')
    d.launch()
    d.recovery()
    assert d.state()['active'] is None and d.state()['pending'] is None
    assert not d.markers()
    provider = d.run('shell', 'content', 'query', '--uri', 'content://' + SHELL + '.probe', check=False)
    # Android content emits provider exception to stdout or stderr depending on platform.
    assert 'Row:' not in provider
    d.run('shell', 'am', 'broadcast', '-n', SHELL + '/' + PACKAGE + '.ProbeReceiver')
    d.run('shell', 'am', 'startservice', '-n', SHELL + '/' + PACKAGE + '.ProbeService')
    assert not d.markers()
    assert d.state()['active'] is None
    print('PASS empty launcher recovery, receiver/service no payload execution, provider no success', flush=True)

    d.install('broken')
    d.failed_application()
    assert d.state()['quarantined'] and d.state()['active']['version'] == 2
    assert d.state()['healthy'] is None and not d.markers()
    d.stop()
    d.launch()
    assert d.state()['quarantined']
    print('PASS caught Application startup failure quarantines durably without rollback', flush=True)

    d.install('B', fresh=False)
    d.launch()
    d.healthy(3)
    assert d.markers()['application'] == 'B'
    print('PASS higher embedded APK repairs quarantine without data reset', flush=True)

    d.install('incomplete')
    for _ in range(2):
        d.launch()
        d.stop()
    d.launch()
    d.recovery()
    assert d.state()['quarantined'] and d.state()['incomplete'] == 2
    print('PASS two incomplete cold starts pause automatic payload entry', flush=True)

    d.install('B')
    d.launch()
    d.healthy(3)
    # Accepted B remains runnable after APK replacement with embedded A, but
    # that older embedded release must not bypass the persisted lineage floor.
    d.install('A', fresh=False)
    d.launch()
    d.healthy(3)
    d.stop()
    path = 'no_backup/paravoid-v1/generations/' + d.state()['active']['directory'] + '/components/resources.apk'
    d.corrupt(path)
    d.launch()
    d.recovery()
    assert d.state()['quarantined'] and d.state()['failure'] == 'INTEGRITY'
    d.install('B', fresh=False)
    d.launch()
    d.healthy(3)
    print('PASS corrupt materialized resources rejected; old embedded release cannot bypass floor; identical-byte repair preserves state', flush=True)

    d.stop()
    d.corrupt('no_backup/paravoid-v1/selection')
    d.launch()
    d.recovery()
    assert d.run('exec-out', 'run-as', SHELL, 'cat', 'no_backup/paravoid-v1/selection').strip() == 'corrupt'
    print('PASS corrupt journal fails closed, recovery route remains usable, no silent state reset', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--minify', action='store_true', help='Build fixture VPKs with the experimental R8 payload pass')
    parser.add_argument('--serial')
    parser.add_argument('--avd')
    args = parser.parse_args()
    if args.build:
        build(args.minify)
    if args.serial or args.avd:
        assert args.serial and args.avd
        device = Device(args.serial, args.avd)
        print('DEVICE', args.serial, device.run('shell', 'getprop', 'ro.build.fingerprint').strip(), flush=True)
        test(device)
    else:
        assert args.build, 'Choose --build or an explicit --serial and --avd'
