"""Exercise enabled controls in the current shell UI, including its scroll cards."""
import re
import xml.etree.ElementTree as ET

ALIASES = {
    'Check now': ('Check again', 'Check for updates', 'Retry now', 'Try again'),
    'Retry update access': ('Try again', 'Retry now', 'Check again'),
    'Cancel download': ('Cancel download', 'Cancel check', 'Cancel retry'),
    'Restart app…': ('Restart app…', 'Restart to apply update'),
    'Stop and restart': ('Restart',), 'Cancel': ('Not now', 'Cancel'),
}


def tap(adb, ui, label):
    labels = {v.lower() for v in ALIASES.get(label, (label,))}
    for attempt in range(12):
        nodes = list(ET.fromstring(ui()).iter('node'))
        node = next((n for n in nodes if n.attrib.get('text', '').lower() in labels
                     and n.attrib.get('enabled') == 'true'), None)
        if node is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            if x2 > x1 and y2 > y1:
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
        # Read screen bounds, rather than assuming a particular phone resolution.
        bounds = [list(map(int, re.findall(r'\d+', n.attrib['bounds']))) for n in nodes]
        width = max(b[2] for b in bounds); height = max(b[3] for b in bounds)
        top, bottom = str(height // 4), str(height * 3 // 4)
        adb('shell', 'input', 'swipe', str(width // 2), bottom if attempt < 6 else top,
            str(width // 2), top if attempt < 6 else bottom, '250')
    raise AssertionError('Missing enabled control: ' + label + ': ' + ui())
