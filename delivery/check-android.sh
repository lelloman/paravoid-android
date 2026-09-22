#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/lelloman/Android/Sdk}}"
output=$(mktemp -d /tmp/paravoid-delivery-android.XXXXXX)
trap 'rm -rf "$output"' EXIT
mapfile -t sources < <(find src android/src ../paravoid-contract/src/main/java -name '*.java' -print)
javac -source 11 -target 11 -Xlint:-options -cp "$sdk/platforms/android-36/android.jar" -d "$output" "${sources[@]}"
echo 'Delivery Android 36 source compilation passed (not device validation)'
