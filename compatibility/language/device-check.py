#!/usr/bin/env python3
"""Compare each language/library mechanism in cold and restored production processes."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(label, fn):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}")


def prefs(app):
    try:
        raw = adb("shell", "run-as", app, "cat", "shared_prefs/language-probe.xml", check=False)
        return {n.get("name"): n.text if n.tag == "string" else n.get("value") for n in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def report(app):
    return json.loads(prefs(app).get("report", "{}"))


def check_mode(mode):
    shell = mode == "paravoidAndroid"
    app = "com.lelloman.paravoidcompat.language." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.language.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/language-compatibility-{mode}-debug.apk"
    adb("install", "-r", str(apk))
    adb("shell", "pm", "clear", app)  # Dedicated fixture data only.
    token = str(uuid.uuid4())
    def launch():
        adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", app + "/" + launcher,
            "--es", "probeRun", token)
    launch()
    cold = wait_for("cold report", lambda: r if (r := report(app)).get("run") == token else None)
    assert cold["restored"] is False
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_for("state saved", lambda: prefs(app).get("savedPid") == str(cold["pid"]))
    time.sleep(1)
    pid = adb("shell", "pidof", app)
    assert pid.isdecimal() and pid == str(cold["pid"])
    adb("shell", "run-as", app, "kill", "-9", pid)
    wait_for("process exits", lambda: not adb("shell", "pidof", app, check=False))
    launch()
    restored = wait_for("restored report", lambda: r if (r := report(app)).get("run") == token
        and r.get("pid") != cold["pid"] else None)
    assert restored["restored"] is True, restored
    failures = []
    for stage, value in (("cold", cold), ("restored", restored)):
        assert len(value["results"]) == 14, value
        for name, result in value["results"].items():
            print(f"{mode} {stage} {name}: {result}", flush=True)
            if result != "PASS": failures.append((mode, stage, name, result))
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
        print("PASS: 14 checks in each mode, before and after process death (56 assertions).")
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "LanguageProbe:E"))
        raise
