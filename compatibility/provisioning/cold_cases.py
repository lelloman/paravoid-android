"""Single-process cold-start DEX stages, never targets non-fixture packages."""
import hashlib
import io
import subprocess
import zipfile

from archive_profile import pack, stored_zip


def exercise(*, app, policy, payloads, release_key, artifact, state, save, server,
             base_head, publish, run, adb, serial):
    digest = None
    previous_process = None
    previous_calls = 0
    installation = None
    root = "files/cold-" + policy["contract"] + "/"

    def selected(name):
        return subprocess.run(["adb", "-s", serial, "exec-out", "run-as", app, "cat", root + name],
                              check=True, capture_output=True, timeout=30).stdout

    def overwrite(name, data):
        # Explicit fault injection into this debuggable fixture's private selection files only.
        # Shell v2 transports stdin EOF and waits for dd's exit; raw exec-in can truncate on disconnect.
        subprocess.run(["adb", "-s", serial, "shell", "-T", "run-as", app, "dd", "of=" + root + name],
                       input=data, check=True, capture_output=True, timeout=30)
        assert selected(name) == data, "Fault injection/restore did not write the exact requested bytes"

    def offer(version, variant, damaged=False):
        nonlocal digest
        identity = dict(applicationId=app, contract=policy["contract"], releaseId=f"dex-{version}", payloadVersion=version)
        blob = pack(identity, {"code/classes.dex": ("code", payloads[variant])}, release_key)
        if damaged:
            with zipfile.ZipFile(io.BytesIO(blob)) as archive:
                entries = [(i.filename, archive.read(i)) for i in archive.infolist()]
            entries[-1] = ("code/classes.dex", payloads["a"] if variant == "b" else payloads["b"])
            blob = stored_zip(entries)  # CRCs valid, inventory digest wrong, outer head signature valid.
        artifact.write_bytes(blob)
        digest = hashlib.sha256(blob).hexdigest()
        state["apps"][app]["release"] = identity["releaseId"]
        save()
        head = dict(base_head, releaseId=identity["releaseId"], payloadVersion=version,
                    revision=version, size=len(blob), sha256=digest)
        publish(head)
        return head

    def check(label, cold_status, value="none", status="verified", reason=None, http=None, restart=True, version=None, failure=None):
        nonlocal previous_process, previous_calls, installation
        result = run(label, status, reason, http=http, restart=restart, expected_digest=digest)
        assert result["coldStatus"] == cold_status and result["coldValue"] == value, (label, result)
        assert result["coldOwnedLoader"] == (cold_status == "loaded"), result
        if version is not None:
            assert result["coldVersion"] == str(version), result
        if failure is not None:
            assert result["coldFailure"] == failure, result
        if previous_process is not None:
            assert (result["coldProcess"] != previous_process) == restart, "Cold/hot process boundary not exercised"
            if not restart and cold_status == "loaded":
                assert result["coldCalls"] > previous_calls, "Warm check did not execute the retained class"
        previous_process = result["coldProcess"]
        previous_calls = result["coldCalls"]
        if installation is None:
            installation = result["installation"]
        assert result["installation"] == installation, "App data lost"
        return result

    installed = adb("shell", "pm", "path", app).splitlines()
    assert len(installed) == 1 and installed[0].startswith("package:")
    apk_path = installed[0][len("package:"):]
    apk_hash = adb("shell", "sha256sum", apk_path)

    head_a = offer(100, "a")
    check("download A does not execute it", "empty")
    check("same process still has no payload", "empty", restart=False)
    check("cold startup activates A", "loaded", "payload-A", version=100)
    active_a = selected("active.bin")
    server.fault = "offline"
    check("offline cold startup runs A", "loaded", "payload-A", "http-error", http=503)
    server.fault = "corrupt"
    offer(101, "b")
    check("corrupt download cannot replace A", "loaded", "payload-A", "rejected", "artifact-integrity", restart=False)
    assert selected("active.bin") == active_a
    server.fault = None
    offer(102, "b")
    check("stage B while A stays loaded", "loaded", "payload-A", restart=False)
    assert selected("active.bin") == active_a
    pending_b = selected("pending.bin")
    check("second Activity still executes A", "loaded", "payload-A", restart=False)
    check("cold startup activates B", "loaded", "payload-B", version=102)
    assert selected("active.bin") == pending_b
    active_b = selected("active.bin")
    offer(103, "a", damaged=True)
    check("inner DEX tampering rejected", "loaded", "payload-B", "rejected", "component-integrity", restart=False)
    assert selected("active.bin") == active_b
    check("rejected update leaves B runnable", "loaded", "payload-B", "rejected", "component-integrity")
    publish(head_a)
    check("old signed A cannot replace B", "loaded", "payload-B", "rejected", "replay")
    offer(104, "failing")
    check("signed broken code is staged only", "loaded", "payload-B", restart=False)
    server.fault = "offline"
    check("broken startup enters recovery without rollback", "recovery", status="http-error", http=503, failure="InvocationTargetException")
    assert selected("active.bin") == active_b
    check("recovery persists across restart", "recovery", status="http-error", http=503)
    server.fault = None
    offer(105, "a")
    check("new forward recovery release staged", "recovery", restart=False)
    check("cold startup activates recovery release", "loaded", "payload-A", version=105)
    active_recovery = selected("active.bin")
    offer(106, "b")
    check("stage B for disk-tampering control", "loaded", "payload-A", restart=False)
    pending = selected("pending.bin")
    overwrite("pending.bin", pending[:-1] + bytes([pending[-1] ^ 1]))
    server.fault = "offline"
    check("tampered pending file never executes", "recovery", status="http-error", http=503, failure="cold-integrity")
    assert selected("active.bin") == active_recovery
    server.fault = None
    offer(107, "b")
    check("verified release replaces corrupt pending state", "recovery", restart=False)
    check("repaired cold startup runs B", "loaded", "payload-B", version=107)
    healthy = selected("active.bin")
    overwrite("active.bin", healthy[:-1] + bytes([healthy[-1] ^ 1]))
    server.fault = "offline"
    check("active bytes are reverified before execution", "recovery", status="http-error", http=503, failure="cold-integrity")
    overwrite("active.bin", healthy)  # Test-only restoration; not a production recovery API.
    check("restored verified file runs B offline", "loaded", "payload-B", "http-error", http=503)
    if state["apps"][app]["mode"] == "key":
        server.fault = None
        state["apps"][app]["keys"] = {}
        save()
        check("revoked download grant does not erase active B", "loaded", "payload-B", "http-error", http=401)
    server.fault = None
    assert adb("shell", "sha256sum", apk_path) == apk_hash, "Shell APK changed during DEX updates"
