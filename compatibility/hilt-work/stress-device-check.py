#!/usr/bin/env python3
"""Real WorkManager chains/retry/cancellation; no instrumentation or fake scheduler."""
import json
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
from stress_assertions import verify_cancelled_reopen
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(label, fn, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(0.4)
    raise AssertionError(f"Timed out: {label}")


def reports(app):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/work-stress.xml", check=False)
    try:
        return {n.get("name"): json.loads(n.text) for n in ET.fromstring(raw)}
    except (ET.ParseError, json.JSONDecodeError):
        return {}


def event(app, token, key):
    values = reports(app)
    assert "error" not in values, values.get("error")
    value = values.get(key, {})
    return value if value.get("run") == token else None


def command(app, token, action):
    request = str(uuid.uuid4())
    adb("shell", "am", "broadcast", "-f", "0x20", "-n",
        app + "/com.lelloman.paravoidcompat.hiltwork.StressControl",
        "--es", "probeRun", token, "--es", "command", action, "--es", "request", request)
    return wait_for(action, lambda: value if (value := event(app, token, "snapshot"))
                    and value.get("request") == request else None)


def states(snapshot):
    return {value["id"]: value for value in snapshot["work"]}


def kill(app, expected_pid):
    assert adb("shell", "pidof", app) == str(expected_pid)
    kill_fixture_process(adb, SERIAL, app, str(expected_pid))
    wait_for("process death", lambda: not adb("shell", "pidof", app, check=False))


def scheduled_jobs(app):
    namespaced = int(adb("shell", "getprop", "ro.build.version.sdk")) >= 34
    prefix = r"JOB androidx\.work\.systemjobscheduler:" if namespaced else r"JOB #"
    jobs = re.findall(prefix + r"[^/\n]+/(\d+):[^\n]*" + re.escape(app) + r"/androidx\.work",
                      adb("shell", "dumpsys", "jobscheduler"))
    return namespaced, set(jobs)


def dispatch_after(app, timestamp):
    # Respect WorkManager's persisted initial/backoff deadline using device time.
    wait_for("persisted scheduling deadline", lambda: int(adb("shell", "date", "+%s")) * 1000 >= timestamp, 65)
    namespaced, jobs = scheduled_jobs(app)
    for job in jobs:
        namespace = ["-n", "androidx.work.systemjobscheduler"] if namespaced else []
        # A job may finish naturally between listing and dispatch. Events/states decide success.
        adb("shell", "cmd", "jobscheduler", "run", "-f", *namespace, app, job, check=False)


def scenario(app, behavior):
    adb("shell", "pm", "clear", app)
    # A freshly cleared, receiver-only app stays in standby bucket NEVER on API 36.
    # Establish ordinary user launch eligibility; this writer process is killed below.
    adb("shell", "am", "start", "-W", "-n", app + "/com.lelloman.paravoidcompat.hiltwork.ProbeActivity")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    token = str(uuid.uuid4())
    initial = command(app, token, behavior)
    created = event(app, token, "enqueued")
    root, tail = created["root"], created["tail"]
    assert set(states(initial)) == {root, tail}, initial
    assert states(initial)[root]["state"] == "ENQUEUED" and states(initial)[tail]["state"] == "BLOCKED", initial
    wait_for("OS job registration", lambda: scheduled_jobs(app)[1])
    kill(app, created["pid"])
    dispatch_after(app, states(initial)[root]["next"])
    first = wait_for("cold worker first attempt", lambda: event(app, token, "attempt0"))
    assert first["id"] == root and first["pid"] != created["pid"] and first["graph"] != created["graph"], first
    assert not event(app, token, "tail")
    if behavior == "retry":
        def persisted_retry():
            snapshot = command(app, token, "snapshot")
            row = states(snapshot)[root]
            return snapshot if row["state"] == "ENQUEUED" and row["attempt"] == 1 else None
        retry = wait_for("retry committed before killing process", persisted_retry)
        assert retry["pid"] == first["pid"], retry
        assert states(retry)[tail]["state"] == "BLOCKED", retry
        wait_for("retry OS job registration", lambda: scheduled_jobs(app)[1])
        kill(app, first["pid"])
        dispatch_after(app, states(retry)[root]["next"])
        second = wait_for("retry in another process", lambda: event(app, token, "attempt1"))
        assert second["id"] == root and second["pid"] not in (first["pid"], created["pid"]), second
        assert second["graph"] != first["graph"], second
        delivered = wait_for("dependent receives predecessor output", lambda: event(app, token, "tail"))
        assert delivered["id"] == tail and delivered["value"] == "chain-✓-" + token, delivered
        def succeeded():
            snapshot = command(app, token, "snapshot")
            return snapshot if all(row["state"] == "SUCCEEDED" for row in snapshot["work"]) else None
        complete = wait_for("both work records committed SUCCEEDED", succeeded)
        assert set(states(complete)) == {root, tail}
        assert states(complete)[tail]["output"] == delivered["value"], complete
        kill(app, complete["pid"])
        durable = command(app, token, "snapshot")
        assert durable["pid"] != complete["pid"]
        assert states(durable) == states(complete), durable
    else:
        running = command(app, token, "snapshot")
        assert states(running)[root]["state"] == "RUNNING" and states(running)[tail]["state"] == "BLOCKED", running
        cancelled = command(app, token, "cancel")
        assert set(states(cancelled)) == {root, tail}
        assert all(row["state"] == "CANCELLED" for row in cancelled["work"]), cancelled
        stopped = wait_for("onStopped delivered", lambda: event(app, token, "stopped"))
        assert stopped["id"] == root and stopped["pid"] == first["pid"] and stopped["isStopped"] is True, stopped
        kill(app, stopped["pid"])
        durable = command(app, token, "snapshot")
        assert durable["pid"] != stopped["pid"]
        if verify_cancelled_reopen(states(cancelled), states(durable), root, tail):
            # WorkManager 2.10.1 prunes finished never-enqueued dependents on DB
            # reopen (last_enqueue_time=-1). Cancellation was verified above.
            print(f"INFO {app}: cancelled blocked dependent pruned on database reopen", flush=True)
        time.sleep(3)  # Bounded negative observation, not a forever/exactly-once guarantee.
        assert not event(app, token, "tail")
    print(f"PASS {app}/{behavior}: persisted chain states, real process death and {'retry/Data/factory fallback' if behavior == 'retry' else 'active cancellation/onStopped/blocked dependent'}", flush=True)


if __name__ == "__main__":
    if not SERIAL.startswith("emulator-"):
        raise SystemExit("Use a dedicated emulator; fixture data is cleared and fixture PIDs are killed.")
    failures = []
    for mode in ("normal", "paravoidAndroid"):
        app = "com.lelloman.paravoidcompat.hiltwork.lazy." + ("normal" if mode == "normal" else "paravoid")
        apk = ROOT / f"build/outputs/apk/{mode}/debug/hilt-work-compatibility-{mode}-debug.apk"
        adb("install", "--no-streaming", "-r", str(apk))
        for behavior in ("retry", "hold"):
            try:
                scenario(app, behavior)
            except Exception as error:
                failures.append(f"{app}/{behavior}: {error}")
                print("FAIL " + failures[-1], flush=True)
                print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "WorkStress:E", "ParavoidAndroid:E", "WM-WorkerFactory:E"))
            finally:
                adb("shell", "am", "force-stop", app, check=False)
    if failures:
        raise SystemExit("\n".join(failures))
