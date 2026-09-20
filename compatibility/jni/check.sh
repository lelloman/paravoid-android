#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
if [[ $# -gt 1 || (${1:-} != '' && ${1:-} != '--device') ]]; then
    echo 'Usage: bash compatibility/jni/check.sh [--device]' >&2
    exit 2
fi
if [[ ${1:-} == '--device' ]]; then
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
fi
failed=0
for storage in archive extracted; do
    legacy=false
    if [[ $storage == extracted ]]; then legacy=true; fi
    "$repo_dir/gradlew" -p "$probe_dir" -PnativeLegacyPackaging="$legacy" \
        assembleNormalDebug assembleParavoidAndroidDebug --console=plain
    if [[ ${1:-} == '--device' ]]; then
        if ! python3 "$probe_dir/device-check.py" "$storage"; then failed=1; fi
    else
        python3 "$probe_dir/device-check.py" "$storage" --artifacts-only
    fi
done
exit "$failed"
