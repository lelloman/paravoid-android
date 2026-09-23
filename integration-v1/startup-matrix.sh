#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# != 2 && $# != 4 ]]; then
    echo 'Usage: bash integration-v1/startup-matrix.sh SERIAL AVD [SERIAL AVD]' >&2
    exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME}"
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
common=(--offline --no-daemon --max-workers=2 --console=plain -p compatibility/complete-v1
    assembleNormalDebug assembleParavoidAndroidDebug -Pauthentication=public -Pgeneration=A -PpayloadVersion=1)
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME"); fi
paravoid_logs=compatibility/complete-v1/build/startup-cases
mkdir -p "$paravoid_logs"
for paravoid_fault in provider-constructor provider-create activity-constructor activity-create service-constructor service-create none; do
    "$paravoid_gradle" "${common[@]}" -Pbootstrap=embedded "-PstartupFault=$paravoid_fault" > "$paravoid_logs/$paravoid_fault-build.log" 2>&1
    python3 -u integration-v1/startup-failures.py --serial "$1" --avd "$2" --fault "$paravoid_fault" > "$paravoid_logs/$paravoid_fault-$1.log" 2>&1 &
    paravoid_first=$!
    if [[ $# == 4 ]]; then
        python3 -u integration-v1/startup-failures.py --serial "$3" --avd "$4" --fault "$paravoid_fault" > "$paravoid_logs/$paravoid_fault-$3.log" 2>&1 &
        paravoid_second=$!
    fi
    paravoid_failed=0
    wait "$paravoid_first" || paravoid_failed=1
    if [[ $# == 4 ]]; then wait "$paravoid_second" || paravoid_failed=1; fi
    if [[ $paravoid_failed == 1 ]]; then
        echo "FAIL $paravoid_fault; inspect $paravoid_logs (fault build retained)" >&2
        exit 1
    fi
    echo "PASS $paravoid_fault on supplied disposable emulator(s)"
done
"$paravoid_gradle" "${common[@]}" -Pbootstrap=empty -PstartupFault=none > "$paravoid_logs/restore-build.log" 2>&1
echo 'PASS startup matrix; ordinary empty/public A outputs restored'
