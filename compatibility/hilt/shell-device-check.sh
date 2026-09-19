#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated emulator/device}"
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
app_id=com.lelloman.paravoidcompat.hilt.paravoid
adb -s "$ANDROID_SERIAL" install -r "$probe_dir/build/outputs/apk/paravoidAndroid/debug/hilt-compatibility-paravoidAndroid-debug.apk"

# Both launcher handoff and direct Activity restoration-style entry use a fresh process.
for activity in com.lelloman.paravoidandroid.runtime.LauncherActivity com.lelloman.paravoidcompat.hilt.ProbeActivity; do
    probe_run="$(date +%s%N)"
    adb -s "$ANDROID_SERIAL" shell am force-stop "$app_id"
    adb -s "$ANDROID_SERIAL" shell am start -W -n "$app_id/$activity" --es probeRun "$probe_run"
    passed=false
    for attempt in {1..30}; do
        report="$(adb -s "$ANDROID_SERIAL" shell run-as "$app_id" cat shared_prefs/hilt-probe.xml 2>/dev/null || true)"
        if [[ "$report" == *"<string name=\"run\">$probe_run</string>"* && "$report" == *'<boolean name="passed" value="true"'* ]]; then
            passed=true
            break
        fi
        sleep 0.5
    done
    if [[ "$passed" != true ]]; then
        adb -s "$ANDROID_SERIAL" logcat -d -s AndroidRuntime:E ParavoidAndroid:E | tail -90 >&2
        echo "Shell Hilt assertions did not pass: $activity" >&2
        exit 1
    fi
    echo "Passed cold launch, DI, classloader isolation and recreation: $activity"
done
