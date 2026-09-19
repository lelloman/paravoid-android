#!/usr/bin/env bash
set -euo pipefail

probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
if [[ $# -gt 1 || (${1:-} != '' && ${1:-} != '--device') ]]; then
    echo 'Usage: bash compatibility/hilt/check.sh [--device]' >&2
    exit 2
fi
if [[ ${1:-} == '--device' && -z ${ANDROID_SERIAL:-} ]]; then
    echo 'Set ANDROID_SERIAL to an unlocked test emulator/device.' >&2
    exit 2
fi

"$repo_dir/gradlew" -p "$probe_dir" assembleNormalDebug assembleNormalDebugAndroidTest --console=plain
mkdir -p "$probe_dir/build/compatibility"

if ! "$repo_dir/gradlew" -p "$probe_dir" assembleParavoidAndroidDebug --console=plain >"$probe_dir/build/compatibility/shell-build.log" 2>&1; then
    tail -60 "$probe_dir/build/compatibility/shell-build.log" >&2
    exit 1
fi

if [[ ${1:-} == '--device' ]]; then
    adb -s "$ANDROID_SERIAL" install -r "$probe_dir/build/outputs/apk/normal/debug/hilt-compatibility-normal-debug.apk"
    adb -s "$ANDROID_SERIAL" install -r "$probe_dir/build/outputs/apk/androidTest/normal/debug/hilt-compatibility-normal-debug-androidTest.apk"
    log="$probe_dir/build/compatibility/normal-device.log"
    adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
        com.lelloman.paravoidcompat.hilt.normal.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
    if ! rg --quiet '^OK \([1-9][0-9]* tests?\)' "$log"; then
        tail -60 "$log" >&2
        exit 1
    fi
    echo 'Normal instrumentation tests passed.'
    bash "$probe_dir/shell-device-check.sh"
    bash "$probe_dir/injected-device-check.sh"
fi
echo 'Hilt checks passed with optional paravoid-hilt and the unmodified dependency manifest.'
