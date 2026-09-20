#!/usr/bin/env python3
"""Install once per mode; stage only pinned resource packs between cold launches."""
import hashlib
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


def adb(*args, data=None, binary=False, check=True):
    result = subprocess.run(["adb", "-s", SERIAL, *args], input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=45)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stderr.decode()} {result.stdout.decode(errors='replace')}")
    return result.stdout if binary else result.stdout.decode().strip()


def wait_for(label, read):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        value = read()
        if value:
            return value
        time.sleep(0.4)
    raise AssertionError(f"Timed out: {label}")


def prefs(app):
    try:
        raw = adb("shell", "run-as", app, "cat", "shared_prefs/resources-probe.xml", check=False)
        return {n.attrib["name"]: n.text if n.tag == "string" else n.get("value") for n in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def report(app):
    return json.loads(prefs(app).get("report", "{}"))


def stage(app, name, data, readonly=True):
    path = "files/resource-probe/" + name
    adb("shell", "-T", "run-as", app, "tee", path + ".tmp", data=data, binary=True)
    adb("shell", "run-as", app, "chmod", "444" if readonly else "600", path + ".tmp")
    adb("shell", "run-as", app, "mv", path + ".tmp", path)


def launch(app, launcher, version):
    adb("shell", "am", "force-stop", app)
    stage(app, "selected", version.encode())
    # Poll our report instead of am start -W, which waits indefinitely on expected startup failures.
    adb("shell", "am", "start", "-n", app + "/" + launcher)


def inspect_build():
    root = ROOT / "build/resource-probe"
    def ids(version):
        return dict(line.split(" = ") for line in (root / f"{version}.ids").read_text().splitlines())
    a, b = ids("A"), ids("B")
    common = a.keys() & b.keys()
    assert common and all(a[key] == b[key] for key in common)
    for version in ("A", "B"):
        with zipfile.ZipFile(root / f"{version}.apk") as archive:
            assert "resources.arsc" in archive.namelist()
            assert not any(n.endswith(".dex") for n in archive.namelist())


def check_mode(mode):
    shell = mode == "paravoidAndroid"
    app = "com.lelloman.paravoidcompat.resources." + ("paravoid" if shell else "normal")
    launcher = "com.lelloman.paravoidandroid.runtime.LauncherActivity" if shell else "com.lelloman.paravoidcompat.resources.ProbeActivity"
    apk = ROOT / f"build/outputs/apk/{mode}/debug/resources-compatibility-{mode}-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        assert "assets/probe/message.txt" not in archive.namelist()
        assert b"Resource payload A" not in archive.read("resources.arsc")
        assert b"Resource payload B" not in archive.read("resources.arsc")
        assert not any(n.endswith("panel.xml") or n.endswith("badge.xml") for n in archive.namelist())
    # Start fresh for this disposable fixture; avoid replacement-install disk overhead.
    adb("uninstall", app, check=False)
    adb("install", str(apk))
    adb("shell", "pm", "clear", app)  # Only this dedicated probe package's disposable data.
    adb("shell", "run-as", app, "mkdir", "-p", "files/resource-probe")
    installed = adb("shell", "pm", "path", app).removeprefix("package:")
    def installed_hash():
        return hashlib.sha256(adb("exec-out", "cat", installed, binary=True)).hexdigest()
    initial_hash = installed_hash()
    for version in ("A", "B"):
        stage(app, version + ".apk", (ROOT / f"build/resource-probe/{version}.apk").read_bytes())
    previous = None
    for version in ("A", "B", "A"):
        launch(app, launcher, version)
        value = wait_for(f"fresh payload {version}", lambda: (r if (r := report(app)).get("pid") not in (None, previous) else None))
        assert value["title"] == value["view"] == f"Resource payload {version}", value
        assert value["asset"] == f"asset {version}" and value["italian"] == f"Risorse {version}", value
        expected = 0xff006600 if version == "A" else 0xff000099
        assert value["accent"] == expected - 2**32
        assert value["removed"] == ("Removed in B" if version == "A" else "absent"), value
        assert value["added"] == ("absent" if version == "A" else "Added in B"), value
        adb("shell", "uiautomator", "dump", "/sdcard/paravoid-resources-window.xml")
        ui = adb("shell", "cat", "/sdcard/paravoid-resources-window.xml")
        assert f"Compose: Resource payload {version}" in ui
        assert f'text="Resource payload {version}"' in ui
        previous = value["pid"]
        assert installed_hash() == initial_hash
        print(f"PASS {mode} {version}: Views/Compose, theme, drawable, locale, assets, added/removed resources; unchanged APK", flush=True)
    for contents, readonly, expected in (
        ((ROOT / "build/resource-probe/B.apk").read_bytes() + b"tampered", True, "hash mismatch"),
        ((ROOT / "build/resource-probe/B.apk").read_bytes(), False, "read-only"),
    ):
        adb("shell", "am", "force-stop", app)
        stage(app, "B.apk", contents, readonly)
        old_failure = prefs(app).get("failurePid")
        launch(app, launcher, "B")
        failure = wait_for("rejected resource pack", lambda: (p if (p := prefs(app)).get("failurePid") not in (None, old_failure) else None))
        assert expected in failure["failure"], failure
        assert str(report(app).get("pid")) != failure["failurePid"]
        assert installed_hash() == initial_hash
        print(f"PASS {mode}: rejects {expected}", flush=True)
    adb("shell", "am", "force-stop", app)
    adb("uninstall", app)


if __name__ == "__main__":
    inspect_build()
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        for mode in ("normal", "paravoidAndroid"):
            check_mode(mode)
    except Exception:
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", "ParavoidAndroid:E"))
        raise
