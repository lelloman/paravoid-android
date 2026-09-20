#!/usr/bin/env python3
"""Exercise production APKs through UI actions, recreation and real process death."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(label, fn):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}")


def prefs(app):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/views-probe.xml", check=False)
    try:
        return {n.get("name"): n.text if n.tag == "string" else n.get("value") for n in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def report(app):
    return json.loads(prefs(app).get("report", "{}"))


def click(app, name):
    adb("shell", "uiautomator", "dump", "/data/local/tmp/paravoid-views-ui.xml")
    xml = ET.fromstring(adb("shell", "cat", "/data/local/tmp/paravoid-views-ui.xml"))
    node = next((n for n in xml.iter("node") if n.get("resource-id") == f"{app}:id/{name}"), None)
    if node is None:
        raise AssertionError(f"Missing {app}:id/{name}; foreground UI: {ET.tostring(xml, encoding='unicode')}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def check_mode(mode):
    shell = mode == "paravoidAndroid"
    app = "com.lelloman.paravoidcompat.views." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.views.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/views-compatibility-{mode}-debug.apk"
    adb("install", "-r", str(apk))
    adb("shell", "pm", "clear", app)  # Dedicated fixture data only.
    token = str(uuid.uuid4())
    def launch():
        adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", app + "/" + launcher,
            "--es", "probeRun", token)
    def stage(phase, generation):
        return wait_for(f"{mode}: phase {phase}, generation {generation}",
                        lambda: r if (r := report(app)).get("run") == token
                        and r.get("phase") == phase and r.get("generation") == generation else None)
    snapshots = []
    launch()
    cold = stage(0, 0)
    assert cold["restored"] is False
    snapshots.append(("cold", cold))
    click(app, "advance")
    snapshots.append(("edited", stage(1, 0)))
    click(app, "recreate")
    recreated = stage(1, 1)
    assert recreated["restored"] and recreated["pid"] == cold["pid"]
    snapshots.append(("recreated", recreated))
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_for("state saved", lambda: prefs(app).get("savedPid") == str(cold["pid"])
             and prefs(app).get("savedGeneration") == "1")
    time.sleep(1)
    pid = adb("shell", "pidof", app)
    assert pid.isdecimal() and pid == str(cold["pid"])
    adb("shell", "run-as", app, "kill", "-9", pid)
    wait_for("process exits", lambda: not adb("shell", "pidof", app, check=False))
    launch()
    restored = stage(1, 2)
    assert restored["restored"] and restored["pid"] != cold["pid"]
    snapshots.append(("process-restored", restored))
    click(app, "back")
    snapshots.append(("back-stack-popped", stage(2, 2)))
    failures = []
    for name, snapshot in snapshots:
        assert len(snapshot["results"]) == 12, snapshot
        for test, result in snapshot["results"].items():
            print(f"{mode} {name} {test}: {result}", flush=True)
            if result != "PASS": failures.append((mode, name, test, result))
    adb("shell", "am", "force-stop", app)
    return failures


if __name__ == "__main__":
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        failures = []
        for mode in ("normal", "paravoidAndroid"):
            failures.extend(check_mode(mode))
        if failures:
            raise AssertionError(f"{len(failures)} failed checks: {failures}")
        print("PASS: 12 checks x 5 lifecycle stages x 2 packaging modes (120 assertions).")
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E"))
        raise
