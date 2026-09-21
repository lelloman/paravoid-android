#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
if [[ $# -gt 1 || (${1:-} != '' && ${1:-} != '--device') ]]; then
    echo 'Usage: bash compatibility/resource-split/check.sh [--device]' >&2
    exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
if [[ ${1:-} == '--device' ]]; then
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
fi
"$repo_dir/gradlew" -p "$probe_dir" assembleNormalDebug assembleParavoidAndroidDebug lintNormalDebug lintParavoidAndroidDebug --console=plain
python3 -m unittest discover -s "$probe_dir" -p 'test_*.py'
python3 -c 'import runpy,sys; runpy.run_path(sys.argv[1])["inspect_apks"](sys.argv[2])' "$probe_dir/device-check.py" "$ANDROID_HOME"
if [[ ${1:-} == '--device' ]]; then python3 "$probe_dir/device-check.py"; fi
