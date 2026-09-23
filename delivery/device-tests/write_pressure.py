"""Real ENOSPC during archive/component copying, on a disposable debuggable emulator only."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile


def run(root, serial, app, launcher, archive, server, adb, ui, tap, phase='archive', competing=False):
    assert phase in ('archive', 'components')
    assert archive.stat().st_size > 32 * 1024 * 1024, 'Build with prepare.py --pressure-mib 32'
    store = 'no_backup/paravoid-v1/'
    filler = 'files/paravoid-mid-write-filler'
    def data(path):
        return subprocess.check_output(['adb', '-s', serial, 'exec-out', 'run-as', app, 'cat', path], timeout=45)
    def free_bytes():
        return int(adb('shell', 'run-as', app, 'df', '-k', '.').splitlines()[-1].split()[3]) * 1024
    active = adb('shell', 'run-as', app, 'find', store + 'generations', '-name', 'archive.vpk').splitlines()
    assert len(active) == 1
    digest = hashlib.sha256(data(active[0])).hexdigest()
    main_pid = adb('shell', 'pidof', app).strip()
    owner = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
    assert owner.isdigit() and main_pid.isdigit()
    worker_pid = None
    def reserve():
        result = adb('shell', 'content', 'call', '--uri', 'content://' + app + '.worker',
                     '--method', 'probe.reserve-space', '--arg', str(archive.stat().st_size))
        assert 'pid=' + worker_pid in result, result
        return result
    if competing:
        assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
        worker_pid = adb('shell', 'pidof', app + ':worker').strip()
        assert worker_pid.isdigit() and worker_pid not in (owner, main_pid)
        assert 'result=ADMITTED' in reserve(), 'Worker probe must first succeed without contention'
    source = root / 'paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/GenerationStore.java'
    lines = [i for i, line in enumerate(source.read_text().splitlines(), 1)
             if 'hash.update(buffer, 0, count); if (output != null) output.write' in line]
    assert len(lines) == 1, 'Update the debugger boundary if the copy loop changes'
    gate_lines = [str(lines[0])]
    if phase == 'components':
        components = [i for i, line in enumerate(source.read_text().splitlines(), 1)
                      if 'try (InputStream input = zip.getInputStream(zipped))' in line]
        assert len(components) == 1, 'Update the component debugger boundary'
        gate_lines.append(str(components[0]))
    port = adb('forward', 'tcp:0', 'jdwp:' + owner).strip()
    assert port.isdigit()
    gate = None
    try:
        with tempfile.TemporaryDirectory(prefix='paravoid-enospc-gate-') as tmp:
            markers = Path(tmp)
            subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', tmp,
                            str(root / 'delivery/device-tests/WriteFailureGate.java')], check=True, timeout=45)
            with (markers / 'log').open('w') as log:
                gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', tmp, 'WriteFailureGate',
                                         port, tmp, *gate_lines], stdout=log, stderr=log)
                def wait(name):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        if (markers / name).exists():
                            return
                        if gate.poll() is not None:
                            raise AssertionError('Write gate exited before ' + name + ': ' + (markers / 'log').read_text())
                        time.sleep(.1)
                    raise AssertionError('Write gate timed out at ' + name + ': ' + (markers / 'log').read_text())
                wait('ready'); tap('Check now'); wait('writing')
                staging = adb('shell', 'run-as', app, 'find', store + 'staging', '-name', 'archive.vpk').splitlines()
                assert len(staging) == 1
                size = int(adb('shell', 'run-as', app, 'stat', '-c', '%s', staging[0]))
                if phase == 'archive':
                    assert 0 < size < archive.stat().st_size, 'Must interrupt an actual partial write'
                else:
                    assert size == archive.stat().st_size
                    assert hashlib.sha256(data(staging[0])).digest() == hashlib.sha256(archive.read_bytes()).digest()
                    component_root = str(Path(staging[0]).parent / 'components')
                    partial = adb('shell', 'run-as', app, 'find', component_root, '-type', 'f').splitlines()
                    assert len(partial) == 1, 'Must pause during the first component'
                    relative = partial[0][len(component_root) + 1:]
                    with zipfile.ZipFile(archive) as contents:
                        expected_size = contents.getinfo(relative).file_size
                    written = int(adb('shell', 'run-as', app, 'stat', '-c', '%s', partial[0]))
                    assert 0 < written < expected_size, 'Must interrupt actual component materialization'
                security, selection = data(store + 'security'), data(store + 'selection')
                if competing:
                    assert 'result=UNAVAILABLE' in reserve(), 'Worker bypassed live writer admission'
                    assert int(adb('shell', 'run-as', app, 'stat', '-c', '%s', staging[0])) == size
                adb('shell', 'run-as', app, 'mkdir', '-p', 'files')
                count = free_bytes() // 1048576 + 256
                # Actual allocated blocks, not sparse length. Bound writes by measured
                # device capacity plus 256 MiB; this file alone is deleted in finally.
                fill = subprocess.run(['adb', '-s', serial, 'shell', 'run-as', app, 'dd', 'if=/dev/zero',
                                       'of=' + filler, 'bs=1048576', 'count=' + str(count)],
                                      text=True, capture_output=True, timeout=240)
                assert fill.returncode != 0 and 'No space left on device' in fill.stdout + fill.stderr
                if competing:
                    assert 'result=UNAVAILABLE' in reserve(), 'Disk pressure bypassed writer ownership'
                    assert security == data(store + 'security') and selection == data(store + 'selection')
                (markers / 'resume').touch()
                wait('enospc')
                if competing:
                    # Wait for the real failed writer to unwind and release its claim,
                    # with the disk still full. A second writer must now fail capacity.
                    deadline = time.monotonic() + 30
                    while True:
                        result = reserve()
                        if 'result=INSUFFICIENT_STORAGE' in result:
                            break
                        assert 'result=UNAVAILABLE' in result, result
                        assert time.monotonic() < deadline, 'Writer claim not released after IO'
                        time.sleep(.1)
                adb('shell', 'run-as', app, 'rm', '-f', filler)
                if competing:
                    assert 'result=ADMITTED' in reserve(), 'Worker admission did not recover after freeing space'
                    assert adb('shell', 'pidof', app + ':worker').strip() == worker_pid
                    print('PASS: distinct installed worker refused during live writer/full disk; rejected for capacity after owner IO; admitted after freeing space', serial, flush=True)
                assert gate.wait(timeout=10) == 0
                for _ in range(15):
                    state = ui()
                    if 'Update status: IO' in state:
                        break
                    time.sleep(.25)
                else:
                    raise AssertionError('Missing terminal storage IO result: ' + state)
                assert 'Pending: none' in state and 'A local app generation is available.' in state
                assert security == data(store + 'security') and selection == data(store + 'selection')
                assert hashlib.sha256(data(active[0])).hexdigest() == digest
                assert adb('shell', 'pidof', app).strip() == main_pid
                assert not adb('shell', 'run-as', app, 'find', 'no_backup', '-name', 'paravoid-update-preferences.retry').strip()
                print('PASS: real mid-' + phase + '-copy ENOSPC; active archive, selection/security records and main PID preserved; no network retry', flush=True)
                tap('Retry update access')
                for _ in range(30):
                    if 'Update: READY' in ui():
                        break
                    time.sleep(.25)
                else:
                    raise AssertionError('Retry after freeing space did not succeed')
                assert not adb('shell', 'run-as', app, 'ls', store + 'staging').strip()
                server.shutdown(); server.server_close()
                adb('shell', 'am', 'force-stop', app)
                adb('shell', 'am', 'start', '-W', '-n', launcher)
                assert 'generation=A;asset=payload-asset;java=payload-java-resource' in ui()
                print('PASS: freed space permits retry/abandoned-staging cleanup; active payload cold-starts offline', serial, flush=True)
    finally:
        adb('shell', 'run-as', app, 'rm', '-f', filler)
        if gate is not None and gate.poll() is None:
            gate.terminate(); gate.wait(timeout=10)
        adb('forward', '--remove', 'tcp:' + port)
