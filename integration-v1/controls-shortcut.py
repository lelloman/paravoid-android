#!/usr/bin/env python3
"""After public_bootstrap.py: verify runnable-app controls through Pixel launcher UI.
Only a dedicated disposable emulator is accepted; no install or payload mutation.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('dedicated emulator required')


def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.serial, *parts], text=True, timeout=45)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/paravoid-shortcut-test.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/paravoid-shortcut-test.xml')).iter('node'))


def point(node):
    a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return str((a + c) // 2), str((b + d) // 2)


app = 'com.lelloman.paravoidcompat.complete.paravoid'
assert 'generation=A;asset=payload-asset;java=payload-java-resource' in adb(
    'shell', 'run-as', app, 'cat', 'shared_prefs/probe.xml')
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
size = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
w, h = map(int, size.groups())
adb('shell', 'input', 'swipe', str(w // 2), str(h * 9 // 10), str(w // 2), str(h // 4), '400')
time.sleep(1)
icons = [n for n in nodes() if n.attrib.get('text') == 'Paravoid complete fixture']
assert icons, 'Fixture icon not visible in launcher drawer'
opened = False
for icon in icons:
    x, y = point(icon)
    adb('shell', 'input', 'swipe', x, y, x, y, '900')
    time.sleep(1)
    shortcuts = [n for n in nodes() if n.attrib.get('text') in ('App updates', 'Paravoid app updates')]
    if shortcuts:
        adb('shell', 'input', 'tap', *point(shortcuts[0]))
        opened = True
        break
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
assert opened, 'Shell controls shortcut not found'
for _ in range(10):
    texts = '\n'.join(n.attrib.get('text', '') for n in nodes())
    if 'A local app generation is available.' in texts and 'check now' in texts.lower():
        break
    time.sleep(1)
else:
    raise AssertionError('Shortcut did not open runnable-app controls: ' + texts)
print('PASS: runnable payload -> launcher shortcut -> private shell controls', args.serial)
