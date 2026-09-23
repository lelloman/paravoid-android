#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?JitPack runner must provide an Android SDK}"
paravoid_sdkmanager=$(command -v sdkmanager || true)
if [[ -z "$paravoid_sdkmanager" ]]; then
    for candidate in "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$ANDROID_HOME/tools/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then paravoid_sdkmanager=$candidate; break; fi
    done
fi
[[ -n "$paravoid_sdkmanager" ]] || { echo 'Android sdkmanager is required on the build runner.' >&2; exit 1; }
# The runner must already have accepted the Android SDK licenses.
"$paravoid_sdkmanager" --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" < /dev/null
