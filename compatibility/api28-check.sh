#!/usr/bin/env bash
# Production-APK baseline. Every adb command is scoped to an explicit emulator.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked API 28 emulator}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'Use a dedicated emulator for this destructive fixture-data run.' >&2; exit 2; }
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == 28 ]] || {
    echo 'This runner requires API 28 exactly.' >&2; exit 2;
}
echo 'API 28: OS FileProvider, activity-result and Binder/Bundle production APK probes'
bash "$repo_dir/compatibility/os/check.sh"
python3 "$repo_dir/compatibility/os/device-check.py"
python3 "$repo_dir/compatibility/os/results-device-check.py"
python3 "$repo_dir/compatibility/os/binder-device-check.py"
for fixture in language views; do
    echo "API 28: $fixture"
    bash "$repo_dir/compatibility/$fixture/check.sh" --device
done
echo 'SKIP JNI: payload native-library discovery requires API 29+.'
echo 'SKIP resource packs: public ResourcesLoader requires API 30+.'
echo 'NOT RUN notification/foreground drivers: currently exercise API 33+/34+ permission/type policies; basic features exist on API 28.'
echo 'NOT RUN in this baseline: Compose/Hilt, Room/WorkManager, networking, sample instrumentation and release builds.'
echo 'PASS: selected API 28 baseline (not the full compatibility matrix).'
