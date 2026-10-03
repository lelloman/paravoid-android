#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cd ..
# Delivery is part of the runtime source set and now depends on runtime classes
# and generated Binder sources. Compile that real graph rather than a partial
# javac source list which can silently drift from the shipped Android library.
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
common=(--no-daemon --max-workers=2 --console=plain)
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then
    common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME")
fi
if [[ "${PARAVOID_OFFLINE:-false}" == true ]]; then common+=(--offline); fi
"$paravoid_gradle" "${common[@]}" :paravoid-runtime:compileDebugJavaWithJavac
echo 'Delivery/runtime Android compilation passed (not device validation)'
