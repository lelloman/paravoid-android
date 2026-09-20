"""Exact-PID process death without force-stop or target-side instrumentation."""


def kill_fixture_process(adb, serial, app, pid):
    if not app.startswith("com.lelloman.paravoidcompat.") or not pid.isdecimal() or int(pid) <= 1:
        raise ValueError("Expected a dedicated fixture and a positive app PID")
    if adb("shell", "pidof", app) != pid:
        raise RuntimeError("Fixture PID changed before process-death check")
    try:
        adb("shell", "run-as", app, "kill", "-9", pid)
    except RuntimeError:
        # API 29 Google APIs rev13 denies runas_app -> untrusted_app sigkill.
        # Only a debuggable emulator may use its existing su for this test.
        # No adb root, SELinux changes, force-stop, or physical-device fallback.
        if (not serial.startswith("emulator-")
                or adb("shell", "getprop", "ro.build.version.sdk") != "29"
                or adb("shell", "getprop", "ro.debuggable") != "1"
                or adb("shell", "pidof", app, check=False) != pid):
            raise
        print(f"Process-death harness: API 29 emulator su kills validated {app} PID {pid}", flush=True)
        adb("shell", "su", "0", "kill", "-9", pid)
