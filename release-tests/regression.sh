#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
common=(--no-daemon --max-workers=2 --console=plain)
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then
    common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME")
fi
if [[ "${PARAVOID_OFFLINE:-false}" == true ]]; then common+=(--offline); fi
"$paravoid_gradle" "${common[@]}" :paravoid-gradle-plugin:test :paravoid-hilt:test \
    :paravoid-work:test :paravoid-contract:test :paravoid-runtime:testDebugUnitTest
bash lifecycle-tests/run.sh
bash delivery/test.sh
bash delivery/check-android.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s delivery/device-tests -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s release-tests -p 'test_*.py'
echo 'PASS host/build regression bundle (not installed-device or release approval)'
