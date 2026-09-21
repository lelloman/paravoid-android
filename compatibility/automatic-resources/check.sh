#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
if [[ $# -gt 1 || (${1:-} != '' && ${1:-} != '--device') ]]; then
    echo 'Usage: bash compatibility/automatic-resources/check.sh [--device]' >&2
    exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
if [[ ${1:-} == '--device' ]]; then
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
fi
python3 -m unittest discover -s "$probe_dir" -p 'test_*.py'
python3 "$probe_dir/build-fixture.py"
if [[ ${1:-} == '--device' ]]; then python3 "$probe_dir/device-check.py"; fi
