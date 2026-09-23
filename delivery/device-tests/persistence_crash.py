"""Installed production-controller persistence interruption matrix; disposable emulator only."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def run(root, serial, app, adb, ui, tap, retry_response):
    store = 'no_backup/paravoid-v1/'
    preferences = 'no_backup/paravoid-update-preferences'

    def data(path, optional=False):
        result = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as', app, 'cat', path],
                                capture_output=True, timeout=45)
        # adb exec-out can report exit zero even when remote cat failed.
        if optional and b'No such file' in result.stderr + result.stdout:
            return None
        assert result.returncode == 0 and not result.stdout.startswith(b'cat:'), result.stderr + result.stdout
        return result.stdout

    def open_controls():
        subprocess.run([sys.executable, str(root / 'integration-v1/controls-shortcut.py'), '--serial', serial],
                       check=True, timeout=150)

    def await_status(status):
        for _ in range(15):
            state = ui()
            if 'Update: ' + status in state:
                return
            time.sleep(.2)
        raise AssertionError('Missing ' + status + ': ' + state)

    main_pid = adb('shell', 'pidof', app).strip()
    assert main_pid.isdigit()
    active = adb('shell', 'run-as', app, 'find', store + 'generations', '-name', 'archive.vpk').splitlines()
    assert len(active) == 1
    digest = hashlib.sha256(data(active[0])).digest()
    security, selection = data(store + 'security'), data(store + 'selection')
    retry_response.set()
    with tempfile.TemporaryDirectory(prefix='paravoid-persistence-device-') as tmp:
        subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', tmp,
                        str(root / 'delivery/device-tests/PersistenceDeathGate.java')], check=True, timeout=45)
        for kind in ('retry', 'cancel'):
            name = 'PendingRetry' if kind == 'retry' else 'CancellationSignal'
            source = root / ('delivery/src/com/lelloman/paravoidandroid/delivery/' + name + '.java')
            markers = [('created', 'p.store(out,' if kind == 'retry' else 'out.write(UUID'),
                       ('written', 'out.getFD().sync();'), ('synced', 'Files.move(tmp, path,'),
                       ('renamed', 'directory.force(true);'), ('directory-synced', 'Files.deleteIfExists(tmp);')]
            for index, (boundary, needle) in enumerate(markers):
                # A real 503 creates a one-hour retry and loads the target classes.
                tap('Check now'); await_status('WAITING_TO_RETRY')
                if kind == 'retry':
                    # An in-flight attempt owns the controller until cancelled;
                    # Check now intentionally does not supersede that owner.
                    tap('Cancel download'); await_status('CANCELLED')
                previous_retry = data(preferences + '.retry', kind == 'retry')
                previous_cancel = data(preferences + '.cancel', True)
                owner = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
                assert owner.isdigit() and owner != main_pid
                lines = [i for i, line in enumerate(source.read_text().splitlines(), 1) if needle in line]
                assert len(lines) == 1, 'Update the source boundary: ' + needle
                case = Path(tmp) / (kind + '-' + boundary); case.mkdir()
                port = adb('forward', 'tcp:0', 'jdwp:' + owner).strip()
                gate = None
                try:
                    with (case / 'log').open('w') as log:
                        gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', tmp,
                            'PersistenceDeathGate', port, str(case), name, str(lines[0])], stdout=log, stderr=log)
                        deadline = time.monotonic() + 20
                        while not (case / 'ready').exists():
                            assert gate.poll() is None and time.monotonic() < deadline, (case / 'log').read_text()
                            time.sleep(.1)
                        tap('Check now' if kind == 'retry' else 'Cancel download')
                        assert gate.wait(timeout=30) == 0, (case / 'log').read_text()
                        assert (case / 'reached').exists()
                    saved_retry = data(preferences + '.retry', True)
                    saved_cancel = data(preferences + '.cancel', True)
                    if kind == 'retry':
                        # Explicit Check now clears the old schedule before making HTTP.
                        assert saved_cancel == previous_cancel
                        if index < 3:
                            assert saved_retry is None, (boundary, saved_retry)
                        else:
                            assert saved_retry and b'retries=1' in saved_retry and b'explicit=true' in saved_retry
                    else:
                        assert saved_retry == previous_retry, 'Process must die before retry cleanup'
                        assert (saved_cancel == previous_cancel) == (index < 3)
                    assert data(store + 'security') == security and data(store + 'selection') == selection
                    assert hashlib.sha256(data(active[0])).digest() == digest
                    assert adb('shell', 'pidof', app).strip() == main_pid
                    open_controls()
                    if kind == 'cancel' and index >= 3:
                        await_status('CANCELLED')
                        assert data(preferences + '.retry', True) is None, 'Published cancellation revived old retry'
                    elif saved_retry is not None:
                        await_status('WAITING_TO_RETRY')
                    print('PASS installed ' + kind + ' death at ' + boundary + ': active process/data/security preserved; cold controller recovers', serial, flush=True)
                finally:
                    if gate is not None and gate.poll() is None:
                        gate.terminate(); gate.wait(timeout=10)
                    adb('forward', '--remove', 'tcp:' + port)
        retry_response.clear()
        tap('Check now'); await_status('READY')
        assert adb('shell', 'pidof', app).strip() == main_pid
        print('PASS explicit update succeeds after persistence crash matrix', serial, flush=True)
