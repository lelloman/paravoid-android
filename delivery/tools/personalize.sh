#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
output=$(mktemp -d /tmp/paravoid-grant-tool.XXXXXX)
trap 'rm -rf "$output"' EXIT
mapfile -t sources < <(find "$script_dir/src" "$script_dir/../../paravoid-contract/src/main/java" -name '*.java' -print)
javac --release 11 -d "$output" "${sources[@]}" "$script_dir/../src/com/lelloman/paravoidandroid/delivery/ApkGrantReader.java" "$script_dir/../src/com/lelloman/paravoidandroid/delivery/ApkPolicyReader.java"
PARAVOID_GRANT_TOOL_CLASSES="$output" python3 "$script_dir/apk_personalize.py" "$@"
