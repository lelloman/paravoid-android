"""Shared UI/process helpers; all commands require an explicit dedicated serial."""
from pathlib import Path
import re
import runpy
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
baseline = runpy.run_path(str(ROOT / 'device-check.py'))
adb, report, PEER = (baseline[name] for name in ('adb', 'report', 'PEER'))


def wait_for(label, predicate):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError('Timed out: ' + label)


def tap(text):
    def locate():
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/paravoid-os-ui.xml')
        raw = adb('shell', 'cat', '/data/local/tmp/paravoid-os-ui.xml')
        for node in ET.fromstring(raw).iter('node'):
            if node.get('text', '').casefold() == text.casefold():
                left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
                adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
                return True
        return False
    try:
        wait_for('UI text ' + text, locate)
    except Exception:
        print(adb('shell', 'cat', '/data/local/tmp/paravoid-os-ui.xml', check=False), flush=True)
        raise


def kill_target(app, expected_pid):
    pid = adb('shell', 'pidof', app)
    assert pid.isdecimal() and pid == str(expected_pid), (pid, expected_pid)
    adb('shell', 'run-as', app, 'kill', '-9', pid)
    wait_for('target process exits', lambda: not adb('shell', 'pidof', app, check=False))


def install(mode):
    app = 'com.lelloman.paravoidcompat.os.' + ('paravoid' if mode == 'paravoidAndroid' else 'normal')
    adb('install', '-r', str(ROOT / f'build/outputs/apk/{mode}/debug/os-compatibility-{mode}-debug.apk'))
    adb('shell', 'pm', 'clear', app)
    adb('shell', 'pm', 'clear', PEER)
    return app


def launch(app, mode, scenario, token):
    launcher = ('com.lelloman.paravoidandroid.runtime.LauncherActivity' if mode == 'paravoidAndroid'
                else 'com.lelloman.paravoidcompat.os.ProbeActivity')
    adb('shell', 'am', 'start', '-W', '-n', app + '/' + launcher,
        '--es', 'scenario', scenario, '--es', 'probeRun', token)


def prepare():
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('install', '-r', str(ROOT / 'peer/build/outputs/apk/debug/peer-debug.apk'))


def cleanup(app):
    adb('shell', 'am', 'force-stop', app, check=False)
    adb('shell', 'am', 'force-stop', PEER, check=False)
