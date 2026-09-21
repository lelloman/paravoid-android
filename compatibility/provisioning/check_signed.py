#!/usr/bin/env python3
"""Autonomous signed-profile experiment, restricted to explicitly selected emulators.

Uses fresh host-only signing keys and only the four existing fixture packages.
Does not install on phones or execute downloaded bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from apk_record import personalize_bytes
from check import ROOT, build, command
from server import Handler, Server
from signed_profile import new_key, public_der, sign


class HostileHandler(Handler):
    """Fault injection is test-only, never an exposed server administration API."""
    def do_GET(self):
        self.server.requests += 1
        super().do_GET()

    def reply(self, status, body=b"", **headers):
        fault = self.server.fault
        head = self.path.endswith("/head")
        if head and fault == "redirect":
            return super().reply(302, Location=f"http://127.0.0.1:{self.server.trap_port}/leak")
        if head and fault == "unsolicited304":
            return super().reply(304, ETag='"absent"')
        if not head and status in (200, 206):
            if fault == "corrupt":
                body = bytes([body[0] ^ 1]) + body[1:]
            elif fault == "wrong-range":
                headers["Content_Range"] = "bytes 1-8/999"
            elif fault == "full":
                body = self.server.artifact.read_bytes()
                status = 200
                headers.pop("Content_Range", None)
        return super().reply(status, body, **headers)


class Trap(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.server.hits += 1
        self.send_response(500)
        self.end_headers()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--archive", action="store_true", help="Run signed component-inventory vectors instead")
    args = parser.parse_args()
    import re
    assert re.fullmatch(r"emulator-\d+", args.serial), "Explicit emulator serial required"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    assert sdk, "Set ANDROID_HOME"

    def adb(*parts, check=True):
        return command("adb", "-s", args.serial, *parts, check=check).strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Refusing physical device"
    for _ in range(60):
        if adb("shell", "getprop", "sys.boot_completed") == "1":
            break
        time.sleep(1)
    else:
        raise AssertionError("Emulator did not boot")
    api = adb("shell", "getprop", "ro.build.version.sdk")
    print(f"Signed-profile tests on API {api}", flush=True)
    signer = Path(sdk) / "build-tools/36.0.0/apksigner"
    stages, packages = [], []
    with tempfile.TemporaryDirectory(prefix="paravoid-signed-") as directory:
        temp = Path(directory)
        issuer, publisher, rogue = new_key(), new_key(), new_key()
        release_key = new_key()
        trust = temp / "assets/probe-trust"
        trust.mkdir(parents=True)
        (trust / "issuer.der").write_bytes(public_der(issuer))
        (trust / "publisher.der").write_bytes(public_der(publisher))
        policy = dict(audience="fixture-store", contract=secrets.token_hex(32), channel="test")
        if args.archive:
            policy["artifactProfile"] = "stored-inventory-1"
            (trust / "release.der").write_bytes(public_der(release_key))
        (trust / "policy.json").write_text(json.dumps(policy))
        build(3, trust.parent)
        artifact = temp / "artifact.bin"
        artifact.write_bytes(b"Signed harmless fixture data. Never execute these bytes.\n")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        config, head_file = temp / "server.json", temp / "head.sig"
        state = {"apps": {}}
        raw_key = secrets.token_urlsafe(32)

        def save():
            next_file = temp / "next.json"
            next_file.write_text(json.dumps(state))
            next_file.replace(config)

        server = Server(("127.0.0.1", 0), config)
        server.RequestHandlerClass = HostileHandler
        server.fault, server.requests, server.artifact = None, 0, artifact
        trap = ThreadingHTTPServer(("127.0.0.1", 0), Trap)
        trap.hits = 0
        server.trap_port = trap.server_port
        threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (server, trap)]
        for thread in threads:
            thread.start()
        ports = []
        try:
            for port in (server.server_port, trap.server_port):
                adb("reverse", f"tcp:{port}", f"tcp:{port}")
                ports.append(port)
            for mode in ("normal", "paravoidAndroid"):
                for access in ("keyed", "publicAccess"):
                    output = ROOT / f"build/outputs/apk/{mode}{access[0].upper() + access[1:]}/debug"
                    metadata = json.loads((output / "output-metadata.json").read_text())
                    app, original = metadata["applicationId"], output / metadata["elements"][0]["outputFile"]
                    assert app.startswith("com.lelloman.paravoidcompat.provisioning.")
                    packages.append(app)
                    certificate = [line for line in command(signer, "verify", "--verbose", "--print-certs", original).splitlines()
                                   if "certificate SHA-256 digest:" in line]
                    assert certificate
                    state["apps"][app] = dict(mode="key" if access == "keyed" else "public",
                        keys={"a": hashlib.sha256(raw_key.encode()).hexdigest()}, artifact=str(artifact),
                        release="fixture-a", signedHead=str(head_file))
                    save()
                    # Emulator time may lag host time; expiry vectors target the verifier's clock.
                    now = int(adb("shell", "date", "+%s"))
                    grant = dict(applicationId=app, audience=policy["audience"], keyId="a", key=raw_key,
                                 issued=now - 10, expires=now + 1800)
                    base_head = dict(applicationId=app, contract=policy["contract"], channel=policy["channel"],
                        revision=10, releaseId="fixture-a", payloadVersion=10, issued=now - 10,
                        expires=now + 1800, size=artifact.stat().st_size, sha256=digest)

                    def publish(fields=None, key=publisher, damage=False):
                        envelope = sign("head", fields or base_head, key)
                        if damage:
                            lines = envelope.split(b"\n")
                            lines[3] = (b"A" if lines[3][:1] != b"A" else b"B") + lines[3][1:]
                            envelope = b"\n".join(lines)
                        head_file.write_bytes(envelope)

                    def install(record=None):
                        target = original
                        if record is not None:
                            target = temp / "personalized.apk"
                            target.write_bytes(personalize_bytes(original.read_bytes(), record))
                            verification = command(signer, "verify", "--verbose", "--print-certs", target)
                            assert "Verified using v3 scheme (APK Signature Scheme v3): true" in verification
                            assert certificate == [line for line in verification.splitlines() if "certificate SHA-256 digest:" in line]
                        assert "Success" in adb("install", "--no-incremental", "-r", "-d", target)

                    def run(label, status, reason=None, http=None, no_request=False, preserve=False):
                        before = server.requests
                        stage = str(uuid.uuid4())
                        component = ("com.lelloman.paravoidandroid.runtime.LauncherActivity" if mode == "paravoidAndroid"
                            else "com.lelloman.paravoidcompat.provisioning.ProbeActivity")
                        adb("shell", "am", "force-stop", app)
                        launched = adb("shell", "am", "start", "-W", "-n", app + "/" + component,
                            "--ei", "port", str(server.server_port), "--es", "stage", stage)
                        assert "Status: ok" in launched, launched
                        result = None
                        for _ in range(50):
                            raw = adb("shell", "run-as", app, "cat", "files/result.json", check=False)
                            try:
                                result = json.loads(raw)
                            except ValueError:
                                result = None
                            if result and result.get("stage") == stage:
                                break
                            time.sleep(0.2)
                        assert result and result.get("stage") == stage, label
                        assert result["status"] == status, (label, result)
                        assert result["shellReader"] == (mode == "paravoidAndroid"), result
                        if reason:
                            assert result["reason"] == reason, (label, result)
                        if http is not None:
                            assert result["http"] == http, (label, result)
                        if no_request:
                            assert server.requests == before, "Credential sent before grant validation"
                        if status == "verified":
                            assert result["sha256"] == digest, result
                            if args.archive:
                                assert result["components"] == 5, result
                        if preserve:
                            if args.archive:
                                kept = subprocess.run(["adb", "-s", args.serial, "exec-out", "run-as", app,
                                    "cat", "files/signed-artifact.bin"], check=True, capture_output=True, timeout=30).stdout
                                assert hashlib.sha256(kept).hexdigest() == preserved_digest, "Verified archive was replaced on failure"
                            else:
                                assert adb("shell", "run-as", app, "cat", "files/signed-artifact.bin") == artifact.read_text().strip()
                        stages.append(dict(packagingMode=mode, access=access, label=label, **result))
                        print(f"PASS {mode}/{access}: {label}", flush=True)

                    publish()
                    install()
                    if access == "keyed":
                        run("no signed grant", "missing-key", no_request=True)
                        install(b'{"version":1,"applicationId":"example.app","keyId":"a","key":"unsigned"}')
                        run("unsigned grant rejected", "rejected", "envelope", no_request=True)
                        for label, fields, signer_key, reason in (
                            ("wrong issuer", grant, rogue, "signature"),
                            ("publisher cannot issue grants", grant, publisher, "signature"),
                            ("wrong grant app", dict(grant, applicationId="wrong.app"), issuer, "grant-scope"),
                            ("wrong audience", dict(grant, audience="other-store"), issuer, "grant-scope"),
                            ("expired grant", dict(grant, issued=now-200, expires=now-1), issuer, "freshness"),
                            ("future grant", dict(grant, issued=now+300, expires=now+600), issuer, "freshness"),
                        ):
                            install(sign("grant", fields, signer_key))
                            run(label, "rejected", reason, no_request=True)
                        install(sign("grant", grant, issuer))
                    if args.archive:
                        from archive_cases import vectors
                        preserved_digest = None
                        for label, identity, blob, reason in vectors(app, policy["contract"], release_key, publisher):
                            artifact.write_bytes(blob)
                            digest = hashlib.sha256(blob).hexdigest()
                            state["apps"][app]["release"] = identity["releaseId"]
                            save()
                            descriptor = dict(base_head, releaseId=identity["releaseId"], payloadVersion=identity["payloadVersion"],
                                revision=identity["payloadVersion"], size=len(blob), sha256=digest)
                            publish(descriptor)
                            run(label, "rejected" if reason else "verified", reason, preserve=reason is not None)
                            if reason is None:
                                preserved_digest = digest
                        run("verified archive survives cold restart", "verified", http=304, preserve=True)
                        continue
                    server.fault = "unsolicited304"
                    run("304 without cached descriptor", "rejected", "cache")
                    server.fault = None
                    run("trusted signed download", "verified", http=200)
                    run("cold restart uses verified 304 cache", "verified", http=304)
                    for label, fields, key, damage, reason in (
                        ("untrusted publisher", base_head, rogue, False, "signature"),
                        ("issuer cannot publish", base_head, issuer, False, "signature"),
                        ("modified signature", base_head, publisher, True, "signature"),
                        ("wrong app", dict(base_head, applicationId="wrong.app"), publisher, False, "head-scope"),
                        ("wrong contract", dict(base_head, contract="wrong"), publisher, False, "head-scope"),
                        ("wrong channel", dict(base_head, channel="wrong"), publisher, False, "head-scope"),
                        ("expired head", dict(base_head, issued=now-200, expires=now-1), publisher, False, "freshness"),
                        ("future head", dict(base_head, issued=now+300, expires=now+600), publisher, False, "freshness"),
                        ("revision replay", dict(base_head, revision=9), publisher, False, "replay"),
                        ("same revision changed metadata", dict(base_head, expires=now+1700), publisher, False, "equivocation"),
                        ("payload downgrade", dict(base_head, revision=11, payloadVersion=9), publisher, False, "replay"),
                        ("version identity reuse", dict(base_head, revision=11, releaseId="different"), publisher, False, "version-reuse"),
                        ("oversized signed artifact", dict(base_head, revision=11, size=1048577), publisher, False, "bounds"),
                    ):
                        publish(fields, key, damage)
                        run(label, "rejected", reason, preserve=True)
                    publish()
                    for fault, reason in (("corrupt", "artifact-integrity"), ("wrong-range", "range")):
                        server.fault = fault
                        run(fault, "rejected", reason, preserve=True)
                    server.fault = "redirect"
                    run("cross-origin redirect refused", "http-error", http=302, preserve=True)
                    assert trap.hits == 0, "Client followed untrusted redirect"
                    server.fault = "full"
                    run("200 replaces range request", "verified", preserve=True)
                    server.fault = None
                    newer = dict(base_head, revision=11, payloadVersion=11, releaseId="fixture-b")
                    state["apps"][app]["release"] = "fixture-b"
                    save()
                    publish(newer)
                    run("new signed release", "verified", http=200)
                    publish()
                    run("old signed release after restart", "rejected", "replay", preserve=True)
                    expires = int(adb("shell", "date", "+%s")) + 8
                    publish(dict(newer, revision=12, issued=expires-20, expires=expires))
                    run("short-lived descriptor", "verified", http=200)
                    deadline = time.monotonic() + 30
                    while int(adb("shell", "date", "+%s")) <= expires:
                        assert time.monotonic() < deadline, "Emulator clock stopped advancing"
                        time.sleep(0.5)
                    run("304 cannot extend signed expiry", "rejected", "freshness", http=304, preserve=True)
                    if access == "keyed":
                        state["apps"][app]["keys"] = {}
                        save()
                        run("revoked key cannot use cache", "http-error", http=401, preserve=True)
            profile = "archive" if args.archive else "signed"
            report = ROOT / f"build/{profile}-api{api}.json"
            report.write_text(json.dumps(stages, indent=2))
            print(f"PASS: {len(stages)} signed stages; report {report}", flush=True)
        finally:
            for app in packages:
                adb("shell", "am", "force-stop", app, check=False)
            for port in ports:
                adb("reverse", "--remove", f"tcp:{port}", check=False)
            for s in (server, trap):
                s.shutdown(); s.server_close()
            for thread in threads:
                thread.join()


if __name__ == "__main__":
    main()
