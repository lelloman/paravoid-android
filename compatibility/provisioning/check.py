#!/usr/bin/env python3
"""Build and test on an explicitly selected emulator. Never targets a phone.

Installs/replaces only four fixture packages without clearing app data; leaves
them installed and force-stopped. Starts/stops its own loopback server and reverse
port mapping. Temporary APKs contain random TEST credentials, never real keys.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile

from apk_record import personalize
from server import Server

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def command(*args, check=True):
    return subprocess.run([str(a) for a in args], check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, timeout=180).stdout


def build(version, trust_dir=None):
    report = ROOT / f"build/provisioning-v{version}.log"
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w") as output:
        extra = [] if trust_dir is None else [f"-PprobeTrustDir={trust_dir}"]
        result = subprocess.run([str(REPO / "gradlew"), "-p", str(ROOT), "assembleDebug",
                                 "--console=plain", "--max-workers=4", f"-PprobeVersion={version}"] + extra,
                                cwd=REPO, stdout=output, stderr=subprocess.STDOUT, timeout=240)
    assert result.returncode == 0, f"Build failed: {report}"


def dex(archive):
    return b"".join(archive.read(n) for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    assert re.fullmatch(r"emulator-\d+", args.serial), "Explicit emulator serial required"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    assert sdk, "Set ANDROID_HOME"
    signer = Path(sdk) / "build-tools/36.0.0/apksigner"

    def adb(*parts, check=True):
        return command("adb", "-s", args.serial, *parts, check=check).strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Refusing physical device"
    for _ in range(45):
        if adb("shell", "getprop", "sys.boot_completed") == "1":
            break
        time.sleep(1)
    else:
        raise AssertionError("Emulator did not boot")
    print("Testing emulator API", adb("shell", "getprop", "ro.build.version.sdk"), flush=True)
    build(1)
    packages = []
    stages = []
    with tempfile.TemporaryDirectory(prefix="paravoid-provisioning-") as directory:
        temp = Path(directory)
        artifact = temp / "artifact.bin"
        artifact.write_bytes(b"Paravoid provisioning test: never execute these bytes.\n")
        expected_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        config = temp / "server.json"
        state = {"apps": {}}
        secrets_by_app = {}
        original_signers = {}

        def save():
            next_file = temp / "next.json"
            next_file.write_text(json.dumps(state))
            next_file.replace(config)

        def verify(apk):
            output = command(signer, "verify", "--verbose", "--print-certs", apk)
            assert re.search(r"Verified using v3 scheme .*: true", output), output
            certificates = [line for line in output.splitlines() if "certificate SHA-256 digest:" in line]
            assert certificates
            return certificates

        def original(mode, access):
            output = ROOT / f"build/outputs/apk/{mode}{access[0].upper() + access[1:]}/debug"
            info = json.loads((output / "output-metadata.json").read_text())
            return info["applicationId"], output / info["elements"][0]["outputFile"]

        for mode in ("normal", "paravoidAndroid"):
            for access in ("keyed", "publicAccess"):
                app, apk = original(mode, access)
                assert app.startswith("com.lelloman.paravoidcompat.provisioning.")
                packages.append(app)
                original_signers[app] = verify(apk)
                secrets_by_app[app] = {name: secrets.token_urlsafe(32) for name in ("a", "b")}
                state["apps"][app] = dict(mode="key" if access == "keyed" else "public",
                    keys={"a": hashlib.sha256(secrets_by_app[app]["a"].encode()).hexdigest()},
                    artifact=str(artifact), release="fixture-a")
                with zipfile.ZipFile(apk) as archive:
                    host = dex(archive)
                    reader = b"Lcom/lelloman/paravoidandroid/runtime/ApkProvisioningProbe;"
                    activity = b"Lcom/lelloman/paravoidcompat/provisioning/ProbeActivity;"
                    assert reader in host
                    if mode == "paravoidAndroid":
                        assert activity not in host
                        with zipfile.ZipFile(io.BytesIO(archive.read("assets/paravoid/module.zip"))) as payload:
                            assert activity in dex(payload)
            # Negative control: changing signed ZIP content must still fail verification.
            _, apk = original(mode, "keyed")
            damaged = bytearray(apk.read_bytes())
            with zipfile.ZipFile(apk) as archive:
                info = archive.getinfo("AndroidManifest.xml")
                offset = info.header_offset
                import struct
                name, extra = struct.unpack_from("<HH", damaged, offset + 26)
                damaged[offset + 30 + name + extra] ^= 1
            target = temp / f"tampered-{mode}.apk"
            target.write_bytes(damaged)
            failure = subprocess.run([str(signer), "verify", str(target)], capture_output=True)
            assert failure.returncode != 0, "Tampered content unexpectedly verified"

        save()
        server = Server(("127.0.0.1", 0), config)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_port
        adb("reverse", f"tcp:{port}", f"tcp:{port}")

        def install(apk):
            assert "Success" in adb("install", "--no-incremental", "-r", "-d", str(apk))

        def personalized(mode, label, key_id="a", wrong_app=False, wrong_key=False):
            app, source = original(mode, "keyed")
            record = dict(version=1, applicationId="example.wrong" if wrong_app else app,
                          keyId=key_id, key=secrets.token_urlsafe(32) if wrong_key else secrets_by_app[app][key_id])
            target = temp / f"{mode}-{label}.apk"
            target.write_bytes(personalize(source.read_bytes(), record))
            assert verify(target) == original_signers[app], "APK signing identity changed"
            return target

        def run(mode, access, label, expected, version=1, key_id=None):
            app, _ = original(mode, access)
            stage = str(uuid.uuid4())
            component = ("com.lelloman.paravoidandroid.runtime.LauncherActivity" if mode == "paravoidAndroid"
                         else "com.lelloman.paravoidcompat.provisioning.ProbeActivity")
            adb("shell", "am", "force-stop", app)
            launched = adb("shell", "am", "start", "-W", "-n", app + "/" + component,
                           "--ei", "port", str(port), "--es", "stage", stage)
            assert "Status: ok" in launched, launched
            result = None
            for _ in range(35):
                raw = adb("shell", "run-as", app, "cat", "files/result.json", check=False)
                try:
                    result = json.loads(raw)
                except ValueError:
                    result = None
                if result and result.get("stage") == stage:
                    break
                time.sleep(0.2)
            assert result and result.get("stage") == stage, f"No result for {label}"
            assert result["status"] == expected, (label, result)
            assert result["version"] == version, (label, result)
            assert result["shellReader"] == (mode == "paravoidAndroid"), (label, result)
            if key_id:
                assert result["keyId"] == key_id
            if expected == "downloaded":
                assert result["sha256"] == expected_digest and result["conditional"] == 304
            if expected == "http-error":
                assert result["http"] == 401, result
            stages.append(dict(packagingMode=mode, access=access, label=label, **result))
            print(f"PASS {mode}/{access}: {label} ({expected})", flush=True)
            return result

        try:
            markers = {}
            for mode in ("normal", "paravoidAndroid"):
                app, apk = original(mode, "keyed")
                install(apk)
                markers[app] = run(mode, "keyed", "no record", "missing-key")["installation"]
                install(personalized(mode, "wrong-app", wrong_app=True))
                run(mode, "keyed", "wrong app record", "invalid-record")
                install(personalized(mode, "wrong-key", wrong_key=True))
                run(mode, "keyed", "wrong key", "http-error", key_id="a")
                install(personalized(mode, "key-a"))
                run(mode, "keyed", "key A", "downloaded", key_id="a")
                run(mode, "keyed", "key A cold restart", "downloaded", key_id="a")
                state["apps"][app]["keys"] = {}
                save()
                run(mode, "keyed", "key A revoked", "http-error", key_id="a")
                run(mode, "keyed", "revoked cold restart", "http-error", key_id="a")
                # Revocation did not erase the last successful harmless transfer.
                kept = adb("shell", "run-as", app, "cat", "files/artifact.bin")
                assert kept == artifact.read_text().strip()
                _, public_apk = original(mode, "publicAccess")
                install(public_apk)
                run(mode, "publicAccess", "public without record", "downloaded")

            build(2)
            for mode in ("normal", "paravoidAndroid"):
                app, _ = original(mode, "keyed")
                state["apps"][app]["keys"] = {"b": hashlib.sha256(secrets_by_app[app]["b"].encode()).hexdigest()}
                save()
                install(personalized(mode, "key-b", key_id="b"))
                result = run(mode, "keyed", "APK v2 replaces key A with B", "downloaded", version=2, key_id="b")
                assert result["installation"] == markers[app], "APK update lost app data"
                run(mode, "keyed", "key B cold restart", "downloaded", version=2, key_id="b")
                # A copied/old personalized APK cannot resurrect revoked server authorization.
                # Debug-only downgrade is intentional here, never a production update policy.
                install(temp / f"{mode}-key-a.apk")
                run(mode, "keyed", "old APK key A remains revoked", "http-error", version=1, key_id="a")
                _, unprovisioned = original(mode, "keyed")
                install(unprovisioned)
                run(mode, "keyed", "record removed by APK replacement", "missing-key", version=2)
            report = ROOT / f'build/device-api{adb("shell", "getprop", "ro.build.version.sdk")}.json'
            report.write_text(json.dumps(stages, indent=2))
            print(f"PASS: {len(stages)} device stages; report {report}", flush=True)
        finally:
            for package in packages:
                adb("shell", "am", "force-stop", package, check=False)
            adb("reverse", "--remove", f"tcp:{port}", check=False)
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    main()
