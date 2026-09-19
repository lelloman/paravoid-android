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

expect_blocker() {
    local label="$1" expected="$2"
    shift 2
    local log="$probe_dir/build/compatibility/$label.log"
    if "$repo_dir/gradlew" -p "$probe_dir" assembleParavoidAndroidDebug --console=plain "$@" >"$log" 2>&1; then
        echo "Paravoid build unexpectedly succeeded ($label); update the compatibility expectations." >&2
        exit 1
    fi
    if ! rg --fixed-strings --quiet "$expected" "$log"; then
        tail -60 "$log" >&2
        echo "Unexpected build failure; expected blocker was not observed ($label)." >&2
        exit 1
    fi
    echo "Confirmed unsupported case: $label"
}

expect_blocker manifest 'ParavoidAndroid example does not yet support manifest receiver components.'
expect_blocker application-hierarchy 'The manifest Application must directly extend ParavoidAndroidApplication for this experiment.' \
    -PhiltProbeMinimalManifest=true

if [[ ${1:-} == '--device' ]]; then
    "$repo_dir/gradlew" -p "$probe_dir" connectedNormalDebugAndroidTest --console=plain
fi
echo 'Normal Hilt builds pass. Paravoid Hilt remains unsupported; expected build blockers confirmed.'
