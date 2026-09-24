#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$probe_dir/../.." && pwd)"

"$repo_dir/gradlew" -p "$probe_dir" assembleNormalDebug assembleParavoidAndroidDebug \
    -PminifyPayload=false --console=plain
python3 "$probe_dir/verify.py" baseline

"$repo_dir/gradlew" -p "$probe_dir" assembleParavoidAndroidDebug \
    -PminifyPayload=true --console=plain
python3 "$probe_dir/verify.py" compare
