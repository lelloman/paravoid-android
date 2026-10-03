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


def probe(serial, flavor, component, seed_directory=None):
    package = APP + (".paravoid" if flavor == "paravoidAndroid" else "")
    apk = ROOT / f"build/outputs/apk/{flavor}/debug/minification-compatibility-{flavor}-debug.apk"
    assert apk.is_file(), f"Build the {flavor} APK first: {apk}"
    adb(serial, "uninstall", package, check=False)
    if seed_directory is not None:
        seed = seed_directory / ("shell.apk" if flavor == "paravoidAndroid" else "normal.apk")
        assert seed.is_file(), f"Missing unminified upgrade seed: {seed}"
        assert "Success" in adb(serial, "install", seed)
        adb(serial, "shell", "am", "start", "-W", "-n", package + "/" + component)
        await_pass(serial, flavor + " seed")
        adb(serial, "shell", "am", "force-stop", package)
        original = saved_bytes(serial, package)
        assert original, "Seed must contain existing non-default settings"
    assert "Success" in adb(serial, "install", "-r", apk)
    adb(serial, "shell", "am", "start", "-W", "-n", package + "/" + component)
    await_pass(serial, flavor)
    adb(serial, "shell", "am", "force-stop", package)
    if seed_directory is not None:
        assert saved_bytes(serial, package) == original, "Upgrade rewrote or lost existing settings"
        adb(serial, "shell", "am", "start", "-W", "-n", package + "/" + component)
        await_pass(serial, flavor + " upgraded cold start")
        assert saved_bytes(serial, package) == original
        adb(serial, "shell", "am", "force-stop", package)
        print(f"PASS {flavor}: unminified-to-minified replacement and cold restart preserve exact saved settings", flush=True)


def saved_bytes(serial, package):
    return subprocess.check_output(["adb", "-s", serial, "exec-out", "run-as", package,
                                    "cat", "files/saved-settings.ser"], timeout=45)


def await_pass(serial, flavor):
    deadline = time.monotonic() + 25
    last = ""
    while time.monotonic() < deadline:
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/paravoid-r8-window.xml")
        xml = adb(serial, "shell", "cat", "/sdcard/paravoid-r8-window.xml")
        texts = [node.attrib.get("text", "") for node in ET.fromstring(xml).iter("node")]
        last = " | ".join(text for text in texts if text)
        if any(text.startswith("PASS:") for text in texts):
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
    parser.add_argument("--seed-directory", type=Path, help="Unminified normal.apk/shell.apk for a data-preserving replacement test")
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Only an emulator is accepted"
    assert adb(args.serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    assert adb(args.serial, "emu", "avd", "name").splitlines()[0] == args.avd
    probe(args.serial, "normal", APP + ".MainActivity", args.seed_directory)
    probe(args.serial, "paravoidAndroid", "com.lelloman.paravoidandroid.runtime.LauncherActivity", args.seed_directory)


if __name__ == "__main__":
    main()
