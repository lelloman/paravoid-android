#!/usr/bin/env python3
"""Install both fixture APKs and assert their probe screens on a named emulator."""

import argparse
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
APP = "com.lelloman.paravoidcompat.minification"


def adb(serial, *arguments, check=True):
    command = ["adb", "-s", serial, *(str(part) for part in arguments)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=45)
    if check and result.returncode:
        raise AssertionError(f"{command}: {result.stdout}\n{result.stderr}")
    return result.stdout


def probe(serial, flavor, component):
    package = APP + (".paravoid" if flavor == "paravoidAndroid" else "")
    apk = ROOT / f"build/outputs/apk/{flavor}/debug/minification-compatibility-{flavor}-debug.apk"
    assert apk.is_file(), f"Build the {flavor} APK first: {apk}"
    adb(serial, "uninstall", package, check=False)
    assert "Success" in adb(serial, "install", apk)
    adb(serial, "shell", "am", "start", "-W", "-n", package + "/" + component)
    deadline = time.monotonic() + 25
    last = ""
    while time.monotonic() < deadline:
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/paravoid-r8-window.xml")
        xml = adb(serial, "shell", "cat", "/sdcard/paravoid-r8-window.xml")
        texts = [node.attrib.get("text", "") for node in ET.fromstring(xml).iter("node")]
        last = " | ".join(text for text in texts if text)
        if any(text.startswith("PASS:") for text in texts):
            adb(serial, "shell", "am", "force-stop", package)
            print(f"PASS {flavor}: {last}", flush=True)
            return
        if any(text.startswith("FAIL:") for text in texts):
            break
        time.sleep(.5)
    raise AssertionError(f"{flavor} did not pass: {last}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Only an emulator is accepted"
    assert adb(args.serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    assert adb(args.serial, "emu", "avd", "name").splitlines()[0] == args.avd
    probe(args.serial, "normal", APP + ".MainActivity")
    probe(args.serial, "paravoidAndroid", "com.lelloman.paravoidandroid.runtime.LauncherActivity")


if __name__ == "__main__":
    main()
