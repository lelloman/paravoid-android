#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated unlocked emulator}"
[[ $# == 0 ]] || { echo 'Usage: stress-check.sh' >&2; exit 2; }
bash "$probe_dir/check.sh" lazy
python3 "$probe_dir/stress-device-check.py"
