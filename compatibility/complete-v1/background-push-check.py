#!/usr/bin/env python3
"""Real shell WebSocket/FGS lifecycle checks on an explicitly selected disposable emulator.

Build with -PbackgroundPush first. Only this fixture's package is installed/cleared.
The local server tests transport lifetime; it does not authorize or publish releases.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import socket
import socketserver
import struct
import subprocess
import threading
import time
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
APP = 'com.lelloman.paravoidcompat.complete.paravoid'
ACTIVITY = 'com.lelloman.paravoidcompat.complete.MainActivity'
SERVICE = 'com.lelloman.paravoidandroid.runtime.PushForegroundService'


def await_(label, check, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = check()
        if value:
            return value
        time.sleep(.2)
    raise AssertionError('Timed out: ' + label)


class Events(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self):
        super().__init__(('127.0.0.1', 18765), Handler)
        self.lock = threading.Lock()
        self.connections = {}
        self.subscriptions = []
        self.pongs = 0

    def connected(self):
        with self.lock:
            return set(self.connections)

    def ping(self, identity):
        with self.lock:
            sock = self.connections[identity]
            previous = self.pongs
        sock.sendall(b'\x89\x05probe')
        await_('WebSocket pong', lambda: self.pongs > previous)

    def disconnect(self, identity):
        with self.lock:
            sock = self.connections.get(identity)
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        identity = None
        try:
            request = self.rfile.readline().decode('ascii')
            headers = {}
            while True:
                line = self.rfile.readline().decode('ascii').strip()
                if not line:
                    break
                name, value = line.split(':', 1)
                headers[name.lower()] = value.strip()
            if not request.startswith('GET /v1/events ') or headers.get('upgrade', '').lower() != 'websocket':
                self.wfile.write(b'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                return
            accept = base64.b64encode(hashlib.sha1((headers['sec-websocket-key'] +
                '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
            self.wfile.write(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
                accept + b'\r\nSec-WebSocket-Protocol: paravoid.updates.v1\r\n\r\n')
            while True:
                first = self.rfile.read(2)
                if len(first) != 2:
                    return
                opcode, size = first[0] & 15, first[1] & 127
                assert first[1] & 128 and first[0] & 128
                if size == 126:
                    size = struct.unpack('>H', self.rfile.read(2))[0]
                assert size <= 4096
                mask = self.rfile.read(4)
                raw = self.rfile.read(size)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(raw))
                if opcode == 1:
                    subscription = json.loads(data)
                    assert subscription['type'] == 'subscribe' and subscription['applicationId'] == APP
                    with self.server.lock:
                        identity = len(self.server.subscriptions) + 1
                        self.server.subscriptions.append(subscription)
                        self.server.connections[identity] = self.request
                elif opcode == 9:
                    self.wfile.write(bytes([0x8a, len(data)]) + data)
                elif opcode == 10:
                    with self.server.lock:
                        self.server.pongs += 1
                elif opcode == 8:
                    return
        except (OSError, ValueError):
            pass
        finally:
            if identity is not None:
                with self.server.lock:
                    self.server.connections.pop(identity, None)


def run(serial, avd):
    def adb(*args):
        result = subprocess.run(['adb', '-s', serial, *args], text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=30)
        if result.returncode == 1 and args[:2] == ('shell', 'pidof'):
            return ''
        result.check_returncode()
        return result.stdout.strip()

    assert serial.startswith('emulator-'), 'Physical devices are intentionally unsupported'
    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    assert adb('emu', 'avd', 'name').splitlines()[0] == avd
    await_('emulator boot', lambda: adb('shell', 'getprop', 'sys.boot_completed') == '1', timeout=120)
    apk = ROOT / 'build/outputs/paravoid/paravoidAndroidDebug/shell.apk'
    assert apk.exists(), 'Build assembleParavoidAndroidDebug -PbackgroundPush first'
    adb('install', '-r', str(apk))
    adb('shell', 'pm', 'clear', APP)
    adb('reverse', 'tcp:18765', 'tcp:18765')
    server = Events()
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def launch(command=None):
        component = ACTIVITY if command else 'com.lelloman.paravoidandroid.runtime.LauncherActivity'
        args = ['shell', 'am', 'start', '-n', APP + '/' + component]
        if command:
            args += ['--es', 'backgroundPushCommand', command]
        adb(*args)

    def foreground_service():
        dump = adb('shell', 'dumpsys', 'activity', 'services', APP)
        return SERVICE in dump and 'isForeground=true' in dump

    def background():
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')

    def background_toggle():
        adb('shell', 'uiautomator', 'dump', '/sdcard/background-push-ui.xml')
        xml = adb('shell', 'cat', '/sdcard/background-push-ui.xml')
        return next((node for node in ET.fromstring(xml).iter('node')
            if node.get('text') == 'Keep update connection in background'), None)

    def toggle_background(enabled):
        launch('controls')
        for _ in range(8):
            node = background_toggle()
            if node is not None:
                break
            adb('shell', 'input', 'swipe', '500', '1700', '500', '800', '350')
        assert node is not None, 'Missing background connection toggle'
        assert node.get('checked') == str(not enabled).lower(), 'Incorrect persisted/default toggle state'
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        await_('saved UI toggle', lambda: (node is not None and node.get('checked') == str(enabled).lower())
            if (node := background_toggle()) is not None else False)

    try:
        launch()
        await_('default visible-app subscription', server.connected)
        assert not foreground_service(), 'Background service must default to off'
        toggle_background(True)
        print('PASS shell updates toggle defaults off and enables the foreground service', flush=True)
        await_('foreground service', foreground_service)
        identity = max(await_('initial subscription', server.connected))
        background()
        time.sleep(2)
        assert foreground_service() and server.connected() == {identity}
        server.ping(identity)
        owner = adb('shell', 'pidof', APP + ':paravoid_updates')
        maps = adb('shell', 'run-as', APP, 'cat', '/proc/' + owner + '/maps')
        assert '/generations/' not in maps, 'Update owner mapped payload code'
        print('PASS background Activity stop retains the same live socket and shell-only foreground service', flush=True)

        server.disconnect(identity)
        identity = max(await_('background reconnect', lambda: server.connected() - {identity}))
        server.ping(identity)
        print('PASS connection failure reconnects while app remains backgrounded', flush=True)

        toggle_background(False)
        await_('persistent pause', lambda: not foreground_service())
        background()
        await_('paused background socket closed', lambda: not server.connected())
        launch()
        await_('ordinary visible-app socket', server.connected)
        assert not foreground_service(), 'Opening app overrode persistent pause'
        background()
        await_('ordinary socket stops in background', lambda: not server.connected())
        print('PASS pause survives app reopen and preserves ordinary visible-app push', flush=True)

        toggle_background(True)
        await_('resumed service', foreground_service)
        await_('resumed subscription', server.connected)
        background()
        time.sleep(2)
        server.ping(max(server.connected()))
        print('PASS explicit resume restores background connection', flush=True)

        old_pid = adb('shell', 'pidof', APP)
        launch('restart')
        new_pid = await_('coordinated app restart', lambda: pid if
            (pid := adb('shell', 'pidof', APP)) and pid != old_pid else None)
        await_('service after restart', foreground_service)
        await_('connection after restart', server.connected)
        time.sleep(2)
        assert adb('shell', 'pidof', APP) == new_pid, 'Unexpected restart loop'
        server.ping(max(server.connected()))
        print('PASS coordinated restart stops update owner and launches a stable new app process with push', flush=True)
    finally:
        adb('shell', 'am', 'force-stop', APP)
        adb('reverse', '--remove', 'tcp:18765')
        for identity in server.connected():
            server.disconnect(identity)
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    args = parser.parse_args()
    run(args.serial, args.avd)
