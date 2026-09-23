"""Installed foreground sticky-service restart; requires natural Android respawn evidence."""
import subprocess
import time
import xml.etree.ElementTree as ET


def run(args, app, adb, nodes, tap, old_main, old_worker, expected_generation='A', require_launch=False):
    component = app + '/com.lelloman.paravoidcompat.complete.ProbeService$Worker'
    def record():
        xml = adb('shell', 'run-as', app, 'cat', 'shared_prefs/sticky-worker.xml')
        return {n.attrib['name']: n.attrib.get('value', n.text) for n in ET.fromstring(xml)}
    def pid(name):
        return subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', name],
                              text=True, capture_output=True, timeout=15).stdout.strip()
    recovery = pid(app + ':paravoid_recovery')
    try:
        initial = record()
        assert initial['pid'] == old_worker and initial['restarted'] == 'false' and initial['generation'] == 'A'
        before = adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection').split()[0]
        tap('Restart app…'); tap('Cancel')
        assert pid(app) == old_main and pid(app + ':worker') == old_worker
        assert adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection').split()[0] == before
        tap('Restart app…'); tap('Stop and restart')
        # No startservice/provider request is allowed here: require an actual
        # framework-issued null Intent to prove natural sticky-service recreation.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            observed = record()
            worker = pid(app + ':worker')
            if worker and worker != old_worker and observed['pid'] == worker and observed['restarted'] == 'true':
                break
            time.sleep(.5)
        else:
            raise AssertionError('No natural sticky respawn within 60s: ' + str(observed))
        assert observed['generation'] == expected_generation, observed
        assert pid(app + ':paravoid_recovery') == recovery
        marker = 'generation=' + expected_generation + ';asset=payload-asset;java=payload-java-resource'
        for _ in range(15):
            text = '\n'.join(n.attrib.get('text', '') for n in nodes())
            if marker in text or 'Could not safely restart.' in text:
                break
            time.sleep(.25)
        if 'Could not safely restart.' in text:
            assert not require_launch, 'This activation test requires an actual fresh payload launch: ' + text
            assert any(n.attrib.get('text', '').lower() == 'restart app…' and n.attrib.get('enabled') == 'true' for n in nodes())
            outcome = 'restart refused safely with usable recovery'
        else:
            assert marker in text, text
            assert pid(app) and pid(app) != old_main
            outcome = 'fresh payload launched'
        # Ensure the guard does not continue killing the naturally respawned worker.
        time.sleep(6)
        assert pid(app + ':worker') == worker and record()['pid'] == worker
        assert pid(app + ':paravoid_recovery') == recovery
        print('PASS: foreground sticky worker naturally respawned as ' + expected_generation + ' with null Intent; ' + outcome
              + '; recovery survives; no repeated kills', args.serial, flush=True)
    finally:
        # These images can return 255 despite successfully stopping the service.
        # Require the CLI response AND disappearance, never ignore failure blindly.
        stopped = subprocess.run(['adb', '-s', args.serial, 'shell', 'am', 'stopservice',
                                  '-n', "'" + component + "'"], text=True, capture_output=True, timeout=45)
        output = stopped.stdout + stopped.stderr
        assert stopped.returncode in (0, 255) and ('Service stopped' in output or 'not running' in output), output
        for _ in range(20):
            if 'ProbeService$Worker' not in adb('shell', 'dumpsys', 'activity', 'services', app):
                break
            time.sleep(.25)
        else:
            raise AssertionError('Sticky fixture service survived explicit cleanup')
