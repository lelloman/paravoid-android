#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
if [[ $# -gt 1 || (${1:-} != '' && ${1:-} != '--device') ]]; then
    echo 'Usage: bash compatibility/views/check.sh [--device]' >&2
    exit 2
fi
if [[ ${1:-} == '--device' ]]; then
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
fi
"$repo_dir/gradlew" -p "$probe_dir" assembleNormalDebug assembleParavoidAndroidDebug --console=plain
if [[ ${1:-} == '--device' ]]; then python3 "$probe_dir/device-check.py"; fi
