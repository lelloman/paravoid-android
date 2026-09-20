#!/usr/bin/env python3
"""Run a persisted WorkManager job in a fresh process and verify its Room write."""
import os
import argparse
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
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(description, fn, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.4)
    raise AssertionError(f"Timed out: {description}")


def result(app, key, token):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/storage-probe.xml", check=False)
    try:
        return ET.fromstring(raw).find(f"string[@name='{key}']").text == token
    except (ET.ParseError, AttributeError):
        return False


def check_mode(mode, configured=False):
    namespaced = int(adb("shell", "getprop", "ro.build.version.sdk")) >= 34
    suffix = "normal" if mode == "normal" else "paravoid"
    app = "com.lelloman.paravoidcompat.storage." + ("configured." if configured else "") + suffix
    component = f"{app}/com.lelloman.paravoidcompat.storage.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/storage-compatibility-{mode}-debug.apk"
    adb("install", "--no-streaming", "-r", str(apk))
    # These two dedicated fixture packages contain test-only data.
    adb("shell", "pm", "clear", app)
    token = str(uuid.uuid4())
    adb("shell", "am", "start", "-n", component, "--es", "probeRun", token)
    wait_for("persisted Room row and enqueued work", lambda: result(app, "enqueued", token))
    def scheduled_job():
        jobs = adb("shell", "dumpsys", "jobscheduler")
        prefix = r"JOB androidx\.work\.systemjobscheduler:" if namespaced else r"JOB #"
        return re.search(prefix + r"[^/\n]+/(\d+):[^\n]*" + re.escape(app) + r"/androidx\.work", jobs)
    job = wait_for("OS job registration", scheduled_job).group(1)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    pid = adb("shell", "pidof", app)
    assert pid.isdecimal()
    kill_fixture_process(adb, SERIAL, app, pid)
    wait_for("original process exits", lambda: not adb("shell", "pidof", app, check=False))
    assert not result(app, "completed", token)
    # Allow WorkManager's own initial delay to elapse; then request OS dispatch if needed.
    time.sleep(21)
    if not result(app, "completed", token):
        namespace = ["-n", "androidx.work.systemjobscheduler"] if namespaced else []
        output = adb("shell", "cmd", "jobscheduler", "run", "-f", *namespace, app, job)
        assert "Running job" in output, output
    wait_for("cold worker reads and updates Room", lambda: result(app, "completed", token))
    worker_pid = adb("shell", "pidof", app)
    assert worker_pid != pid
    if configured:
        values = ET.fromstring(adb("shell", "run-as", app, "cat", "shared_prefs/storage-probe.xml"))
        for key in ("configurationPid", "factoryPid"):
            assert values.find(f"int[@name='{key}']").get("value") == worker_pid, key
    # Kill the worker process too: a third process must read its committed database update.
    assert worker_pid.isdecimal()
    kill_fixture_process(adb, SERIAL, app, worker_pid)
    wait_for("worker process exits", lambda: not adb("shell", "pidof", app, check=False))
    # This is a fresh DB reader, not a saved-task restoration test. API 28 can
    # restore the old root Intent (enqueue) when reusing the previous task.
    adb("shell", "am", "start", "-f", "0x10008000", "-n", component,
        "--es", "probeRun", token, "--ez", "verify", "true")
    wait_for("Room survives both process deaths", lambda: result(app, "verified", token))
    adb("shell", "am", "force-stop", app)
    print(f"PASS {'configured' if configured else 'default'}/{mode}: Room generated DAO, persistent work, cold JobService/Worker and durable worker write", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--configured", action="store_true")
    args = parser.parse_args()
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        for mode in ("normal", "paravoidAndroid"):
            check_mode(mode, args.configured)
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "StorageProbe:E", "WM-WorkerFactory:E"))
        raise
