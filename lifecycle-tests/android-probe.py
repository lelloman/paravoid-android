#!/usr/bin/env python3
"""Run only on the dedicated Track C emulator. No default/physical-device target."""
import concurrent.futures
import os
import selectors
from pathlib import Path
import subprocess
import tempfile

REPO = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get('ANDROID_HOME', '/home/lelloman/Android/Sdk'))
SERIAL = 'emulator-5586'
ADB = ['adb', '-s', SERIAL]
MAIN = 'com.lelloman.paravoidandroid.runtime.lifecycle.AndroidPrimitiveProbe'

def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=45, **kwargs).stdout.strip()

def adb(*arguments):
    return run(ADB + list(arguments))

assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
assert adb('emu', 'avd', 'name').splitlines()[0] == 'Lifecycle36', 'Requires the dedicated Track C AVD'
with tempfile.TemporaryDirectory(prefix='paravoid-android-probe-') as temporary:
    output = Path(temporary)
    classes = output / 'classes'
    classes.mkdir()
    sources = REPO / 'paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle'
    run(['javac', '--release', '11', '-d', str(classes), str(sources / 'AtomicRecord.java'),
         str(sources / 'ProcessLocks.java'), str(REPO / 'lifecycle-tests/AndroidPrimitiveProbe.java')])
    run(['jar', 'cf', str(output / 'classes.jar'), '-C', str(classes), '.'])
    run([str(SDK / 'build-tools/36.1.0/d8'), '--min-api', '30', '--lib', str(SDK / 'platforms/android-36/android.jar'),
         '--output', str(output / 'probe.zip'), str(output / 'classes.jar')])
    remote = '/data/local/tmp/' + output.name
    adb('shell', 'mkdir', '-p', remote)
    adb('push', str(output / 'probe.zip'), remote + '/probe.zip')
    adb('shell', 'chmod', '444', remote + '/probe.zip')
    command = ADB + ['shell', 'dalvikvm', '-cp', remote + '/probe.zip', MAIN]

    def probe(mode, *extra, check=True):
        result = subprocess.run(command + [mode, remote + '/state'] + list(extra),
                                text=True, capture_output=True, timeout=30)
        if check:
            assert result.returncode == 0, result.stderr
        return result

    holders = []
    try:
        for _ in range(2):
            process = subprocess.Popen(command + ['hold', remote + '/state'], text=True,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            holders.append((process, None))
            with selectors.DefaultSelector() as ready:
                ready.register(process.stdout, selectors.EVENT_READ)
                assert ready.select(timeout=20), 'ART lease handshake timed out'
                line = process.stdout.readline().strip()
            assert line.startswith('LEASED '), line + process.stderr.read()
            holders[-1] = (process, line.split()[1])
        assert probe('probe').stdout.strip() == 'BUSY'
        for index, (process, pid) in enumerate(holders):
            adb('shell', 'kill', '-9', pid)  # Injection only: subsequent OS lock probe proves release.
            process.wait(timeout=10)
            assert probe('probe').stdout.strip() == ('BUSY' if index == 0 else 'FREE')
        print('PASS Android shared leases: two ART processes, one death remains busy, final death releases lock')
        for boundary in ('content-synced', 'renamed', 'directory-synced'):
            assert probe('write').stdout.strip() == 'WROTE'
            result = probe('crash', boundary, check=False)
            assert result.returncode == 73, result.stderr
            expected = 'VALUE 1' if boundary == 'content-synced' else 'VALUE 2'
            assert probe('read').stdout.strip() == expected
        print('PASS Android durable records: directory force and interrupted atomic replacement')
        probe('write')
        with concurrent.futures.ThreadPoolExecutor() as pool:
            list(pool.map(lambda _: probe('increment'), range(3)))
        assert probe('read').stdout.strip() == 'VALUE 91'
        print('PASS Android selection lock races: three ART processes, 90 durable transactions')
        print('Device:', SERIAL, adb('shell', 'getprop', 'ro.build.fingerprint'))
    finally:
        for process, pid in holders:
            if process.poll() is None:
                if pid:
                    subprocess.run(ADB + ['shell', 'kill', '-9', pid], capture_output=True, timeout=10)
                process.terminate()
        # Only this script's fresh, dedicated-emulator scratch directory.
        adb('shell', 'rm', '-rf', remote)
