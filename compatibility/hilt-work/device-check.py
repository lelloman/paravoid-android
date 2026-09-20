#!/usr/bin/env python3
"""Production APKs: injected cold worker and Room durability across two process deaths."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))
from device_process import kill_fixture_process
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def prefs(app):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/hilt-work-probe.xml", check=False)
    try:
        return {node.get("name"): node.text for node in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def wait_for(label, fn, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}")


def result(app, key, token):
    value = prefs(app)
    if value.get("error", "").startswith(token + ":"):
        raise AssertionError(value["error"])
    return value if value.get(key) == token else None


def check_mode(mode, policy):
    app = f"com.lelloman.paravoidcompat.hiltwork.{policy}." + ("normal" if mode == "normal" else "paravoid")
    component = f"{app}/com.lelloman.paravoidcompat.hiltwork.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/hilt-work-compatibility-{mode}-debug.apk"
    # Avoid streamed-install digest failures observed with a host-verified APK.
    adb("install", "--no-streaming", "-r", str(apk))
    adb("shell", "pm", "clear", app)  # Dedicated fixture data only.
    token = str(uuid.uuid4())
    try:
        adb("shell", "am", "start", "-n", component, "--es", "probeRun", token)
        writer = wait_for("enqueue through configured HiltWorkerFactory", lambda: result(app, "enqueued", token))
        namespaced = int(adb("shell", "getprop", "ro.build.version.sdk")) >= 34
        prefix = r"JOB androidx\.work\.systemjobscheduler:" if namespaced else r"JOB #"
        def scheduled_job():
            return re.search(prefix + r"[^/\n]+/(\d+):[^\n]*" + re.escape(app) + r"/androidx\.work",
                             adb("shell", "dumpsys", "jobscheduler"))
        job = wait_for("OS job registration", scheduled_job).group(1)
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        pid = adb("shell", "pidof", app)
        kill_fixture_process(adb, SERIAL, app, pid)
        wait_for("writer process exits", lambda: not adb("shell", "pidof", app, check=False))
        assert not result(app, "completed", token)
        time.sleep(21)  # Honor WorkManager's own initial delay before explicit OS dispatch.
        if not result(app, "completed", token):
            namespace = ["-n", "androidx.work.systemjobscheduler"] if namespaced else []
            output = adb("shell", "cmd", "jobscheduler", "run", "-f", *namespace, app, job)
            assert "Running job" in output, output
        worker = wait_for("cold injected worker commits Room update", lambda: result(app, "completed", token))
        worker_pid = adb("shell", "pidof", app)
        assert worker_pid == worker["workerPid"] and worker_pid != pid, worker
        assert worker["workerGraph"] != writer["writerGraph"], worker
        kill_fixture_process(adb, SERIAL, app, worker_pid)
        wait_for("worker process exits", lambda: not adb("shell", "pidof", app, check=False))
        adb("shell", "am", "start", "-f", "0x10008000", "-n", component,
            "--es", "probeRun", token, "--ez", "verify", "true")
        verified = wait_for("third process reads durable Room write", lambda: result(app, "verified", token))
        assert verified["verifiedPid"] not in (pid, worker_pid), verified
        print(f"PASS {policy}/{mode}: generated Hilt worker factory, cold graph/configuration without Activity, Room across three PIDs", flush=True)
    finally:
        adb("shell", "am", "force-stop", app, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("policy", choices=("lazy", "explicit"))
    args = parser.parse_args()
    if not SERIAL.startswith("emulator-"):
        raise SystemExit("Use a dedicated emulator; this driver clears fixture data and kills fixture processes.")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    failures = []
    for mode in ("normal", "paravoidAndroid"):
        try:
            check_mode(mode, args.policy)
        except Exception as error:
            failures.append(f"{args.policy}/{mode}: {error}")
            print("FAIL " + failures[-1], flush=True)
            print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "HiltWorkProbe:E", "WM-WorkerFactory:E"))
    if failures:
        raise SystemExit("\n".join(failures))
