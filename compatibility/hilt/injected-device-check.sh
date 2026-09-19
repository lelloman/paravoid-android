#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated emulator/device}"
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"

await_result() {
    local key="$1" run="$2" report
    for attempt in {1..30}; do
        report="$(adb -s "$ANDROID_SERIAL" shell run-as "$app_id" cat shared_prefs/hilt-probe.xml 2>/dev/null || true)"
        if [[ "$report" == *"<string name=\"$key\">$run</string>"* ]]; then return; fi
        sleep 0.5
    done
    adb -s "$ANDROID_SERIAL" logcat -d -s AndroidRuntime:E ParavoidAndroid:E | tail -90 >&2
    echo "Injected component assertions failed: $app_id $key" >&2
    exit 1
}

for mode in normal paravoidAndroid; do
    suffix=normal
    if [[ "$mode" == paravoidAndroid ]]; then suffix=paravoid; fi
    app_id="com.lelloman.paravoidcompat.hilt.$suffix"
    adb -s "$ANDROID_SERIAL" install -r "$probe_dir/build/outputs/apk/$mode/debug/hilt-compatibility-$mode-debug.apk"
    # Each component must be able to start the process without an Activity.
    adb -s "$ANDROID_SERIAL" shell am force-stop "$app_id"
    run="$(date +%s%N)"
    adb -s "$ANDROID_SERIAL" shell am broadcast --include-stopped-packages \
        -n "$app_id/com.lelloman.paravoidcompat.hilt.InjectedReceiver" --es probeRun "$run"
    await_result injectedReceiverRun "$run"
    echo "Passed cold @AndroidEntryPoint receiver: $mode"

    adb -s "$ANDROID_SERIAL" shell am force-stop "$app_id"
    # Allow a background test start without turning the fixture into a foreground service.
    adb -s "$ANDROID_SERIAL" shell cmd deviceidle tempwhitelist -d 60000 "$app_id"
    for count in 1 2; do
        run="$(date +%s%N)"
        adb -s "$ANDROID_SERIAL" shell am startservice \
            -n "$app_id/com.lelloman.paravoidcompat.hilt.InjectedService" \
            --es probeRun "$run" --ei expectedCreations "$count"
        await_result injectedServiceRun "$run"
    done
    adb -s "$ANDROID_SERIAL" shell cmd deviceidle tempwhitelist -r "$app_id"
    echo "Passed cold @AndroidEntryPoint service and renewed service scope: $mode"
done
