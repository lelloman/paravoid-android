#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"
policy=${1:-lazy}
[[ "$policy" == lazy || "$policy" == explicit ]] || { echo 'Usage: check.sh [lazy|explicit] [--device]' >&2; exit 2; }
[[ $# -le 2 && (${2:-} == '' || ${2:-} == --device) ]] || exit 2
options=()
if [[ "$policy" == explicit ]]; then options+=(-PexplicitWorkInitialization); fi
if [[ ${2:-} == --device ]]; then : "${ANDROID_SERIAL:?Use a dedicated unlocked emulator}"; fi
"$repo_dir/gradlew" -p "$probe_dir" "${options[@]}" assembleNormalDebug assembleParavoidAndroidDebug --console=plain
if [[ ${2:-} == --device ]]; then python3 "$probe_dir/device-check.py" "$policy"; fi
