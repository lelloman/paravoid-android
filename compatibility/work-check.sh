#!/usr/bin/env bash
# Optional WorkManager integration: real APKs, normal packaging as control.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked API 28+ emulator}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'Use a dedicated emulator for this fixture-data run.' >&2; exit 2; }
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ && "$device_api" -ge 28 ]] || {
    echo 'This runner requires API 28 or newer.' >&2; exit 2;
}
echo "API $device_api: Hilt lazy configuration and cold injected worker"
bash "$repo_dir/compatibility/hilt-work/check.sh" lazy --device
echo "API $device_api: non-Hilt lazy configuration and custom WorkerFactory"
bash "$repo_dir/compatibility/storage/check.sh" --configured --device
echo 'PASS: selected WorkManager custom-configuration matrix, normal and shell.'
