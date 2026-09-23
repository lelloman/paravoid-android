#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# != 4 ]]; then
  echo 'Usage: recovery-races.sh emulator-SERIAL AVD emulator-SERIAL AVD' >&2
  exit 2
fi
gradle_bin=${PARAVOID_GRADLE:-./gradlew}
gradle_args=(--no-daemon --max-workers=2 --console=plain)
if [[ -n ${PARAVOID_GRADLE_USER_HOME:-} ]]; then
  gradle_args+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME")
fi
if [[ ${PARAVOID_OFFLINE:-false} == true ]]; then gradle_args+=(--offline); fi
fixture=compatibility/complete-v1
output="$fixture/build/outputs/paravoid/paravoidAndroidDebug"
artifacts=$(mktemp -d /tmp/paravoid-recovery-races.XXXXXX)
echo "Recovery race artifacts retained at $artifacts"
"$gradle_bin" -p "$fixture" "${gradle_args[@]}" assembleParavoidAndroidDebug \
  -Pbootstrap=embedded -Pauthentication=public -Pgeneration=broken -PpayloadVersion=2 -PstartupFault=none
cp "$output/shell.apk" "$artifacts/broken-shell.apk"
"$gradle_bin" -p "$fixture" "${gradle_args[@]}" assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk \
  -Pbootstrap=embedded -Pauthentication=public -Pgeneration=B -PpayloadVersion=3 -PstartupFault=none
mkdir "$artifacts/repair"
cp "$output/payload.vpk" "$output/release.json" "$artifacts/repair/"
for mode in pending stale; do
  extra=()
  if [[ $mode == stale ]]; then extra+=(--activate-repair); fi
  python3 -u integration-v1/quarantine-controls.py --serial "$1" --avd "$2" \
    --apk "$artifacts/broken-shell.apk" --repair "$artifacts/repair" "${extra[@]}" &
  first=$!
  python3 -u integration-v1/quarantine-controls.py --serial "$3" --avd "$4" \
    --apk "$artifacts/broken-shell.apk" --repair "$artifacts/repair" --server-port 18766 "${extra[@]}" &
  second=$!
  status=0
  wait "$first" || status=$?
  wait "$second" || status=$?
  if [[ $status != 0 ]]; then exit "$status"; fi
done
