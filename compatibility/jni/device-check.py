#!/usr/bin/env python3
"""Check native APK entries and JNI behavior in cold/restored production processes."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", os.environ["ANDROID_SERIAL"], *args], text=True,
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
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/jni-probe.xml", check=False)
    try:
        return {n.get("name"): n.text if n.tag == "string" else n.get("value") for n in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def check_artifact(mode, storage):
    apk = ROOT / f"build/outputs/apk/{mode}/debug/jni-compatibility-{mode}-debug.apk"
    with ZipFile(apk) as package:
        for abi in ("x86_64", "arm64-v8a"):
            for lib in ("probe_jni", "probe_dep", "probe_plugin", "c++_shared"):
                entry = package.getinfo(f"lib/{abi}/lib{lib}.so")
                assert entry.compress_type == (ZIP_STORED if storage == "archive" else ZIP_DEFLATED), entry
        if mode == "paravoidAndroid":
            with ZipFile(io.BytesIO(package.read("assets/paravoid/module.zip"))) as payload:
                assert not any(name.endswith(".so") for name in payload.namelist())
    print(f"PASS {storage}/{mode}: both ABIs, all four libraries, APK compression and payload placement", flush=True)
    return apk


def check_mode(mode, storage, apk):
    shell = mode == "paravoidAndroid"
    app = f"com.lelloman.paravoidcompat.jni.{storage}." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.jni.ProbeActivity"
    adb("install", "-r", str(apk))
    adb("shell", "pm", "clear", app)  # Dedicated fixture data only.
    token = str(uuid.uuid4())
    def launch():
        adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", app + "/" + launcher,
            "--es", "probeRun", token)
    def report():
        return json.loads(prefs(app).get("report", "{}"))
    launch()
    cold = wait_for("cold report", lambda: r if (r := report()).get("run") == token else None)
    assert cold["restored"] is False
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_for("state saved", lambda: prefs(app).get("savedPid") == str(cold["pid"]))
    time.sleep(1)
    pid = adb("shell", "pidof", app)
    assert pid.isdecimal() and pid == str(cold["pid"])
    adb("shell", "run-as", app, "kill", "-9", pid)
    wait_for("process exits", lambda: not adb("shell", "pidof", app, check=False))
    launch()
    restored = wait_for("restored report", lambda: r if (r := report()).get("run") == token
                        and r.get("pid") != cold["pid"] else None)
    assert restored["restored"] is True, restored
    failures = []
    for stage, snapshot in (("cold", cold), ("restored", restored)):
        assert len(snapshot["results"]) == 13, snapshot
        for test, result in snapshot["results"].items():
            print(f"{storage}/{mode} {stage} {test}: {result}", flush=True)
            if result != "PASS": failures.append((storage, mode, stage, test, result))
    adb("shell", "am", "force-stop", app)
    return failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("storage", choices=("archive", "extracted"))
    parser.add_argument("--artifacts-only", action="store_true")
    args = parser.parse_args()
    try:
        if not args.artifacts_only:
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb("shell", "wm", "dismiss-keyguard")
        failures = []
        for mode in ("normal", "paravoidAndroid"):
            apk = check_artifact(mode, args.storage)
            if not args.artifacts_only: failures.extend(check_mode(mode, args.storage, apk))
        if failures: raise AssertionError(f"{len(failures)} failed JNI assertions: {failures}")
        if not args.artifacts_only: print(f"PASS {args.storage}: 52 device assertions.")
    except Exception:
        if not args.artifacts_only:
            print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "JniProbe:E"))
        raise
