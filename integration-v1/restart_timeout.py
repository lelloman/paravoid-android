"""Installed timeout using a debugger-scheduled real worker, not mocked process state."""
from pathlib import Path
import subprocess
import tempfile
import time


def run(args, app, adb, nodes, tap, old_main, old_worker):
    root = Path(__file__).resolve().parents[1]
    recovery = adb('shell', 'pidof', app + ':paravoid_recovery').strip()
    def pid(name):
        return subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', name],
                              text=True, capture_output=True, timeout=15).stdout.strip()
    def records():
        return adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection', 'no_backup/paravoid-v1/security')
    source = root / 'paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/RestartProcessGate.java'
    lines = source.read_text().splitlines()
    boundaries = []
    for text in ('long started = platform.elapsed();', 'platform.sleep();', 'return false;'):
        found = [str(i) for i, line in enumerate(lines, 1) if line.strip() == text]
        assert len(found) == 1, 'Update debugger boundary: ' + text
        boundaries.extend(found)
    port = adb('forward', 'tcp:0', 'jdwp:' + recovery).strip()
    gate = None
    try:
        with tempfile.TemporaryDirectory(prefix='paravoid-restart-timeout-') as tmp:
            markers = Path(tmp)
            subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', tmp,
                            str(root / 'integration-v1/RestartTimeoutGate.java')], check=True, timeout=45)
            with (markers / 'log').open('w') as log:
                gate = subprocess.Popen(['java', '--add-modules', 'jdk.jdi', '-cp', tmp,
                                         'RestartTimeoutGate', port, tmp, *boundaries], stdout=log, stderr=log)
                def wait(name):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        if (markers / name).exists(): return
                        if gate.poll() is not None: break
                        time.sleep(.1)
                    raise AssertionError('Missing gate ' + name + ': ' + (markers / 'log').read_text())
                wait('ready')
                assert pid(app) == old_main and pid(app + ':worker') == old_worker
                before = records()
                tap('Restart app…'); tap('Stop and restart'); wait('stopped')
                for _ in range(30):
                    if not pid(app) and not pid(app + ':worker'): break
                    time.sleep(.1)
                else: raise AssertionError('Initial app processes did not disappear')
                # Intentional race injection: a real Android process appears after
                # the kill pass. Do not repeat this request during polling.
                assert 'generation=A' in adb('shell', 'content', 'query', '--uri', 'content://' + app + '.worker')
                worker = pid(app + ':worker')
                assert worker and worker != old_worker and not pid(app)
                assert records() == before
                (markers / 'resume').touch(); wait('timeout')
                assert gate.wait(timeout=10) == 0
                for _ in range(15):
                    current = nodes()
                    if any('Could not safely restart.' in n.attrib.get('text', '') for n in current): break
                    time.sleep(.25)
                else: raise AssertionError('Timeout did not leave recovery refusal visible')
                assert any(n.attrib.get('text', '').lower() == 'restart app…' and n.attrib.get('enabled') == 'true' for n in current)
                assert pid(app + ':paravoid_recovery') == recovery and pid(app + ':worker') == worker
                assert not pid(app) and records() == before
                print('PASS: real worker prevents restart until polling timeout; recovery/worker survive; no launch or record changes; '
                      + (markers / 'timeout').read_text().strip(), args.serial, flush=True)
            # Debugger detached. The normal retry must kill the remaining worker
            # and launch A without adb termination or journal manipulation.
            tap('Restart app…'); tap('Stop and restart')
            for _ in range(20):
                if any('generation=A;asset=payload-asset;java=payload-java-resource' in n.attrib.get('text', '') for n in nodes()): break
                time.sleep(.25)
            else: raise AssertionError('User retry did not recover after timeout')
            assert pid(app) and pid(app) != old_main and not pid(app + ':worker')
            assert pid(app + ':paravoid_recovery') == recovery
            print('PASS: debugger-free confirmed retry stops worker and launches A; recovery survives', args.serial, flush=True)
    finally:
        if gate is not None and gate.poll() is None:
            gate.terminate(); gate.wait(timeout=10)
        adb('forward', '--remove', 'tcp:' + port)
