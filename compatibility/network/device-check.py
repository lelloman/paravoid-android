#!/usr/bin/env python3
"""Run real HTTP requests from production APKs, then repeat after process death."""
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from local_server import ProbeServer

ROOT = Path(__file__).resolve().parent
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(label, fn):
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}")


def prefs(app):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/network-probe.xml", check=False)
    try:
        return {n.get("name"): n.text if n.tag == "string" else n.get("value") for n in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def check_mode(mode, server, port):
    shell = mode == "paravoidAndroid"
    app = "com.lelloman.paravoidcompat.network." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.network.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/network-compatibility-{mode}-debug.apk"
    adb("install", "-r", str(apk))
    adb("shell", "pm", "clear", app)  # Dedicated fixture data only.
    token = str(uuid.uuid4())
    server.tokens.add(token)
    def launch():
        adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", app + "/" + launcher,
            "--es", "probeRun", token, "--es", "baseUrl", f"http://127.0.0.1:{port}/")
    def report():
        return json.loads(prefs(app).get("report", "{}"))
    try:
        launch()
        cold = wait_for("cold network report", lambda: r if (r := report()).get("run") == token else None)
        assert cold["restored"] is False
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        wait_for("state saved", lambda: prefs(app).get("savedPid") == str(cold["pid"]))
        time.sleep(1)
        pid = adb("shell", "pidof", app)
        assert pid.isdecimal() and pid == str(cold["pid"])
        adb("shell", "run-as", app, "kill", "-9", pid)
        wait_for("process exits", lambda: not adb("shell", "pidof", app, check=False))
        launch()
        restored = wait_for("restored network report", lambda: r if (r := report()).get("run") == token
                            and r.get("pid") != cold["pid"] else None)
        assert restored["restored"] is True, restored
        failures = []
        for stage, snapshot in (("cold", cold), ("restored", restored)):
            assert len(snapshot["results"]) == 16, snapshot
            for test, result in snapshot["results"].items():
                print(f"{mode} {stage} {test}: {result}", flush=True)
                if result != "PASS": failures.append((mode, stage, test, result))
            try:
                server.audit(token, snapshot["pid"])
                print(f"PASS {mode}/{stage}: server request audit", flush=True)
            except AssertionError as error:
                failures.append((mode, stage, "server.audit", str(error)))
        return failures
    finally:
        adb("shell", "am", "force-stop", app, check=False)


if __name__ == "__main__":
    server = ProbeServer()
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = None
    try:
        port = int(adb("reverse", "--no-rebind", "tcp:0", f"tcp:{server.server_port}"))
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        failures = []
        for mode in ("normal", "paravoidAndroid"):
            failures.extend(check_mode(mode, server, port))
        if failures: raise AssertionError(f"Network failures: {failures}")
        print("PASS: 64 device assertions and four independent request audits.")
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "NetworkProbe:E"))
        raise
    finally:
        if port is not None: adb("reverse", "--remove", f"tcp:{port}", check=False)
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
