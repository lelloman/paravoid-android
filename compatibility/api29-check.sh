#!/usr/bin/env bash
# Native-loading API boundary: production APKs, normal packaging as control.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked API 29 emulator}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'Use a dedicated emulator for this destructive fixture-data run.' >&2; exit 2; }
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == 29 ]] || {
    echo 'This runner requires API 29 exactly.' >&2; exit 2;
}
echo 'API 29: Compose/Hilt/Navigation, including transitive native libraries'
bash "$repo_dir/compatibility/compose/check.sh" --device
echo 'API 29: JNI, APK-backed and extracted libraries, cold/restored processes'
bash "$repo_dir/compatibility/jni/check.sh" --device
echo 'NOT RUN: other compatibility fixtures, release/AAB splits, ARM/32-bit and physical devices.'
echo 'PASS: selected API 29 native-loading boundary (not the full compatibility matrix).'
