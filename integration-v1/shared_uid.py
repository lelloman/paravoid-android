"""Real PackageManager shared-UID restart refusal, without synthetic process snapshots."""
import re
import subprocess
import time


def run(args, app, adb, nodes, tap, old_main, old_worker):
    peer = 'com.lelloman.paravoidcompat.complete'
    packages = dict(re.findall(r'package:(\S+) uid:(\d+)', adb('shell', 'pm', 'list', 'packages', '-U', peer)))
    assert app in packages and peer in packages and packages[app] == packages[peer], packages
    def pid(name):
        return subprocess.run(['adb', '-s', args.serial, 'shell', 'pidof', name],
                              text=True, capture_output=True, timeout=15).stdout.strip()
    recovery = pid(app + ':paravoid_recovery')
    assert recovery and old_main and old_worker
    def records():
        return adb('shell', 'run-as', app, 'sha256sum', 'no_backup/paravoid-v1/selection', 'no_backup/paravoid-v1/security')
    before = records()
    def reject(expected_peer):
        tap('Restart app…'); tap('Stop and restart')
        for _ in range(15):
            current = nodes()
            if any('Could not safely restart.' in n.attrib.get('text', '') for n in current):
                break
            time.sleep(.25)
        else:
            raise AssertionError('Shared-UID restart did not show refusal')
        assert any(n.attrib.get('text', '').lower() == 'restart app…' and n.attrib.get('enabled') == 'true' for n in current)
        assert pid(app) == old_main and pid(app + ':worker') == old_worker
        assert pid(app + ':paravoid_recovery') == recovery and pid(peer) == expected_peer
        assert records() == before
    assert not pid(peer), 'First check must have an installed but dormant peer'
    reject('')
    print('PASS: actual shared UID with dormant peer refuses restart; all payload/recovery PIDs and records preserved', args.serial, flush=True)
    adb('shell', 'am', 'start', '-W', '-n', peer + '/.MainActivity')
    peer_pid = pid(peer)
    assert peer_pid
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    for _ in range(15):
        if any(n.attrib.get('text', '').lower() == 'restart app…' for n in nodes()):
            break
        time.sleep(.25)
    else:
        raise AssertionError('Did not return from peer to recovery controls')
    reject(peer_pid)
    print('PASS: live same-UID peer also survives refusal; recovery retry remains enabled', args.serial, flush=True)
