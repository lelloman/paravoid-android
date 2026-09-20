#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
device=false
options=()
driver_options=()
for option in "$@"; do
    case "$option" in
        --device) device=true ;;
        --configured) options+=(-PconfiguredWork); driver_options+=(--configured) ;;
        *) echo 'Usage: bash compatibility/storage/check.sh [--configured] [--device]' >&2; exit 2 ;;
    esac
done
if $device; then
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
fi
"$repo_dir/gradlew" -p "$probe_dir" "${options[@]}" assembleNormalDebug assembleParavoidAndroidDebug --console=plain
if $device; then python3 "$probe_dir/device-check.py" "${driver_options[@]}"; fi
