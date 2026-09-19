#!/usr/bin/env python3
"""Run a persisted WorkManager job in a fresh process and verify its Room write."""
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
    return subprocess.run(["adb", "-s", SERIAL, *args], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=check, timeout=40).stdout.strip()


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


def check_mode(mode):
    suffix = "normal" if mode == "normal" else "paravoid"
    app = "com.lelloman.paravoidcompat.storage." + suffix
    component = f"{app}/com.lelloman.paravoidcompat.storage.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/storage-compatibility-{mode}-debug.apk"
    adb("install", "-r", str(apk))
    # These two dedicated fixture packages contain test-only data.
    adb("shell", "pm", "clear", app)
    token = str(uuid.uuid4())
    adb("shell", "am", "start", "-W", "-n", component, "--es", "probeRun", token)
    wait_for("persisted Room row and enqueued work", lambda: result(app, "enqueued", token))
    def scheduled_job():
        jobs = adb("shell", "dumpsys", "jobscheduler")
        return re.search(r"JOB androidx\.work\.systemjobscheduler:[^/\n]+/(\d+):[^\n]*" + re.escape(app) + r"/androidx\.work", jobs)
    job = wait_for("OS job registration", scheduled_job).group(1)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    pid = adb("shell", "pidof", app)
    assert pid.isdecimal()
    adb("shell", "run-as", app, "kill", "-9", pid)
    wait_for("original process exits", lambda: not adb("shell", "pidof", app, check=False))
    assert not result(app, "completed", token)
    # Allow WorkManager's own initial delay to elapse; then request OS dispatch if needed.
    time.sleep(21)
    if not result(app, "completed", token):
        output = adb("shell", "cmd", "jobscheduler", "run", "-f", "-n",
                     "androidx.work.systemjobscheduler", app, job)
        assert "Running job" in output, output
    wait_for("cold worker reads and updates Room", lambda: result(app, "completed", token))
    worker_pid = adb("shell", "pidof", app)
    assert worker_pid != pid
    # Kill the worker process too: a third process must read its committed database update.
    assert worker_pid.isdecimal()
    adb("shell", "run-as", app, "kill", "-9", worker_pid)
    wait_for("worker process exits", lambda: not adb("shell", "pidof", app, check=False))
    adb("shell", "am", "start", "-W", "-n", component, "--es", "probeRun", token, "--ez", "verify", "true")
    wait_for("Room survives both process deaths", lambda: result(app, "verified", token))
    adb("shell", "am", "force-stop", app)
    print(f"PASS {mode}: Room generated DAO, persistent work, cold JobService/Worker and durable worker write", flush=True)


if __name__ == "__main__":
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        for mode in ("normal", "paravoidAndroid"):
            check_mode(mode)
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E", "StorageProbe:E", "WM-WorkerFactory:E"))
        raise
