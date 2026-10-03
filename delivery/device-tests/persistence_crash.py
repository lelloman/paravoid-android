"""Installed UpdateEngine record replacement deaths; disposable emulator only."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import time
from engine_state import OPERATIONS, OWNER_SUFFIX, controls, properties, state


def run(root, serial, app, adb, ui, tap, retry_response, deletion_only=False,
        replacement_retry=False, first_cancel_boundary=None):
    store = 'no_backup/paravoid-v1/'

    def data(path):
        result = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as', app, 'cat', path],
                                capture_output=True, timeout=45, check=True)
        assert b'No such file' not in result.stdout + result.stderr, result.stdout + result.stderr
        return result.stdout

    def await_phase(phase):
        for _ in range(240):
            if state(adb, app).get('phase') == phase:
                return
            time.sleep(.25)
        raise AssertionError('Missing engine phase ' + phase + ': ' + str(state(adb, app)))

    main_pid = adb('shell', 'pidof', app).strip()
    assert main_pid.isdigit()
    active = adb('shell', 'run-as', app, 'find', store + 'generations', '-name', 'archive.vpk').splitlines()
    assert len(active) == 1
    digest = hashlib.sha256(data(active[0])).digest()
    security, selection = data(store + 'security'), data(store + 'selection')
    retry_response.set()
    source = root / 'delivery/src/com/lelloman/paravoidandroid/delivery/PendingRetry.java'
    markers = [('created', 'p.store(out,'), ('written', 'out.getFD().sync();'),
               ('synced', 'Files.move(tmp, path,'), ('renamed', 'directory.force(true);'),
               ('directory-synced', 'Files.deleteIfExists(tmp);')]
    # Cancellation now replaces the unified record; it does not unlink a retry file.
    kinds = ('cancel',) if deletion_only or first_cancel_boundary else ('retry',) if replacement_retry else ('retry', 'cancel')
    with tempfile.TemporaryDirectory(prefix='paravoid-engine-device-') as tmp:
        subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', tmp,
                        str(root / 'delivery/device-tests/PersistenceDeathGate.java')], check=True, timeout=45)
        for kind in kinds:
            for index, (boundary, needle) in enumerate(markers):
                if first_cancel_boundary and boundary != first_cancel_boundary:
                    continue
                if deletion_only and boundary not in ('synced', 'renamed'):
                    continue
                retry_response.delay_seconds = 2 if replacement_retry else 3600
                # Cold observer process as well as the owner: these tests concern
                # durable scheduling, not reusing an in-flight Binder/UI snapshot.
                observer = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
                if observer:
                    assert observer.isdigit() and observer != main_pid
                    adb('shell', 'run-as', app, 'kill', '-9', observer)
                controls(adb, app)
                if state(adb, app).get('kind') != 'NONE':
                    tap('Cancel download'); await_phase('CANCELLED')
                tap('Check now'); await_phase('WAITING_TO_RETRY')
                previous = data(OPERATIONS)
                owner = adb('shell', 'pidof', app + OWNER_SUFFIX).strip()
                assert owner.isdigit() and owner != main_pid
                lines = [i for i, line in enumerate(source.read_text().splitlines(), 1) if needle in line]
                assert len(lines) == 1, 'Update source boundary: ' + needle
                case = Path(tmp) / (kind + '-' + boundary); case.mkdir()
                port = adb('forward', 'tcp:0', 'jdwp:' + owner).strip()
                gate = None
                try:
                    with (case / 'log').open('w') as log:
                        # Select the actual durable phase/attempt written by the engine.
                        gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', tmp,
                            'PersistenceDeathGate', port, str(case), 'PendingRetry', str(lines[0]), '1',
                            'WAITING_TO_RETRY' if kind == 'retry' else 'CANCELLED',
                            '2' if replacement_retry else '1'],
                            stdout=log, stderr=log)
                        deadline = time.monotonic() + 20
                        while not (case / 'ready').exists():
                            assert gate.poll() is None and time.monotonic() < deadline, (case / 'log').read_text()
                            time.sleep(.1)
                        if replacement_retry:
                            wait = max(0, int(properties(previous.decode())['due']) - int(time.time()) + 1)
                            assert wait <= 60, 'Unexpected scheduled retry deadline'
                            time.sleep(wait)
                            adb('shell', 'cmd', 'jobscheduler', 'run', '-f', '-n',
                                'com.lelloman.paravoid.updates', app, str(0x50560001))
                        else:
                            tap('Check now' if kind == 'retry' else 'Cancel download')
                        assert gate.wait(timeout=45) == 0, (case / 'log').read_text()
                        assert (case / 'reached').exists()
                    saved = data(OPERATIONS)
                    record = properties(saved.decode())
                    if kind == 'cancel':
                        assert (saved == previous if index < 3 else record.get('phase') == 'CANCELLED'), (boundary, record, properties(previous.decode()))
                        if index >= 3:
                            assert record['kind'] == 'NONE', 'Committed cancellation retained retry intent'
                    else:
                        # Before final rename the attempt record survives; after it,
                        # the durable HTTP retry survives. Both must recover safely.
                        assert record['kind'] == 'CHECK' and record['explicit'] == 'true', (boundary, record)
                        assert record['attempts'] == ('2' if replacement_retry else '1')
                        assert record['phase'] == ('CHECKING' if index < 3 else 'WAITING_TO_RETRY')
                    remnants = adb('shell', 'run-as', app, 'find', 'no_backup/paravoid-updates-v1',
                                   '-name', '*.tmp').splitlines()
                    assert len(remnants) <= 1, 'Engine deaths accumulated temporary records'
                    assert data(store + 'security') == security and data(store + 'selection') == selection
                    assert hashlib.sha256(data(active[0])).digest() == digest
                    assert adb('shell', 'pidof', app).strip() == main_pid
                    observer = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
                    if observer:
                        assert observer.isdigit() and observer != main_pid
                        adb('shell', 'run-as', app, 'kill', '-9', observer)
                    controls(adb, app)
                    await_phase('CANCELLED' if kind == 'cancel' and index >= 3 else 'WAITING_TO_RETRY')
                    if kind == 'cancel' and index >= 3:
                        assert state(adb, app)['kind'] == 'NONE'
                    print('PASS installed engine ' + kind + ' death at ' + boundary + ': active process/data/security preserved; durable intent recovered', serial, flush=True)
                finally:
                    if gate is not None and gate.poll() is None:
                        gate.terminate(); gate.wait(timeout=10)
                    adb('forward', '--remove', 'tcp:' + port)
        retry_response.clear()
        tap('Check now')
        for _ in range(60):
            current = state(adb, app)
            if current['kind'] == 'NONE' and current['phase'] in ('AVAILABLE', 'READY', 'IDLE'):
                break
            time.sleep(.25)
        else:
            raise AssertionError('Explicit check failed after engine death matrix')
        assert adb('shell', 'pidof', app).strip() == main_pid
        assert not adb('shell', 'run-as', app, 'find', 'no_backup/paravoid-updates-v1', '-name', '*.tmp').strip()
        print('PASS explicit check succeeds after engine persistence crash matrix', serial, flush=True)
