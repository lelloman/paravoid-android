#!/usr/bin/env python3
"""Black-box checks against production APKs; no instrumentation/classloader shortcuts."""
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
SERIAL = os.environ["ANDROID_SERIAL"]


def adb(*args, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stdout}")
    return result.stdout.strip()


def wait_for(description, fn, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {description}")


def report(app):
    raw = adb("shell", "run-as", app, "cat", "shared_prefs/compose-probe.xml", check=False)
    try:
        return json.loads(ET.fromstring(raw).find("string[@name='report']").text)
    except (ET.ParseError, AttributeError, json.JSONDecodeError):
        return {}


def state(app, **expected):
    def matches():
        value = report(app)
        return value if all(value.get(k) == v for k, v in expected.items()) else None
    return wait_for(str(expected), matches)


def tap(label):
    # Read real accessibility nodes and tap their centers, not fixed screen coordinates.
    adb("shell", "uiautomator", "dump", "/sdcard/paravoid-compose-window.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/paravoid-compose-window.xml"))
    node = next(n for n in root.iter("node") if n.get("content-desc") == label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def launch(app, launcher):
    return adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
               "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", f"{app}/{launcher}")


def packaging(apk, shell):
    with zipfile.ZipFile(apk) as archive:
        installed = b"".join(archive.read(n) for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        has_payload = "assets/paravoid/module.zip" in archive.namelist()
        assert has_payload == shell
        if shell:
            assert b"Lcom/lelloman/paravoidcompat/compose/ScreenModel;" not in installed
            assert b"Landroidx/compose/runtime/Composer;" not in installed
            with zipfile.ZipFile(io.BytesIO(archive.read("assets/paravoid/module.zip"))) as bundle:
                dex = [n for n in bundle.namelist() if n.endswith(".dex")]
                assert len(dex) > 1, "Fixture should exercise multi-DEX packaging"
                metadata = bundle.read("module.properties").decode()
                assert "format=2\n" in metadata and f"dexCount={len(dex)}\n" in metadata
                assert set(bundle.namelist()) == {"module.properties", "classes.dex", *(f"classes{i}.dex" for i in range(2, len(dex) + 1))}


def check_mode(mode):
    shell = mode == "paravoidAndroid"
    app = "com.lelloman.paravoidcompat.compose." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.compose.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/compose-compatibility-{mode}-debug.apk"
    packaging(apk, shell)
    adb("install", "-r", str(apk))
    adb("shell", "am", "force-stop", app)
    launch(app, launcher)
    first = state(app, route="list", count=0, local=0, pid=int(adb("shell", "pidof", app)))
    assert first["resource"] == "Compose resource lookup"
    tap("Increment")
    listing = state(app, route="list", count=1, local=1)
    tap("Open detail")
    detail = state(app, route="detail", count=0, local=0)
    assert detail["model"] != listing["model"] and detail["graph"] == listing["graph"]
    for count in (1, 2):
        tap("Increment")
        detail = state(app, route="detail", count=count, local=count)
    current_rotation = adb("shell", "dumpsys", "window", "displays")
    current = int(re.search(r"mRotation=(\d)", current_rotation).group(1))
    adb("shell", "wm", "user-rotation", "lock", "1" if current == 0 else "0")
    rotated = wait_for("rotation recreates Activity", lambda: (r if (r := report(app)).get("activity") != detail["activity"] else None))
    assert all(rotated[k] == detail[k] for k in ("route", "count", "local", "model", "graph", "pid")), rotated
    # Kill only this debuggable app's validated PID after saving state, preserving its task.
    # am kill can spare Android's "previous app" process; force-stop discards task state.
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_for("Android saves the stopped Activity", lambda: f'<string name="savedActivity">{rotated["activity"]}</string>' in adb(
        "shell", "run-as", app, "cat", "shared_prefs/compose-probe.xml"))
    time.sleep(1)
    old_pid = adb("shell", "pidof", app)
    assert old_pid == str(rotated["pid"])
    assert old_pid.isdecimal()
    adb("shell", "run-as", app, "kill", "-9", old_pid)
    wait_for("process really exits", lambda: not adb("shell", "pidof", app, check=False))
    launch(app, launcher)
    restored = wait_for("fresh process restores task", lambda: (r if (r := report(app)).get("pid") not in (None, rotated["pid"])
        and all(r.get(k) == detail[k] for k in ("route", "count", "local")) else None))
    assert all(restored[k] == detail[k] for k in ("route", "count", "local")), restored
    assert all(restored[k] != rotated[k] for k in ("model", "graph", "activity", "pid")), restored
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    back = state(app, route="list", count=1, local=1)
    assert back["model"] != restored["model"] and back["graph"] == restored["graph"]
    tap("Open detail")
    fresh = state(app, route="detail", count=0, local=0)
    assert fresh["model"] != restored["model"]
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    adb("shell", "am", "force-stop", app)
    print(f"PASS {mode}: Compose/resources, navigation scopes/back, rotation, process-death state restoration", flush=True)


if __name__ == "__main__":
    auto = adb("shell", "settings", "get", "system", "accelerometer_rotation")
    rotation = adb("shell", "settings", "get", "system", "user_rotation")
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        for mode in ("normal", "paravoidAndroid"):
            check_mode(mode)
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E"))
        raise
    finally:
        for key, value in (("accelerometer_rotation", auto), ("user_rotation", rotation)):
            if value == "null":
                adb("shell", "settings", "delete", "system", key)
            else:
                adb("shell", "settings", "put", "system", key, value)
