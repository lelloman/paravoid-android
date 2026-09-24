#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output=$(mktemp -d /tmp/paravoid-full-vpk.XXXXXX)
trap 'chmod -R u+w "$output"; rm -rf "$output"' EXIT
javac --release 11 -d "$output/classes" \
  paravoid-recovery-api/src/main/java/com/lelloman/paravoidandroid/recovery/*.java \
  paravoid-contract/src/main/java/com/lelloman/paravoidandroid/contract/*.java \
  paravoid-contract/src/test/java/com/lelloman/paravoidandroid/contract/MetadataTestSupport.java \
  paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/*.java \
  delivery/src/com/lelloman/paravoidandroid/delivery/*.java integration-v1/*.java
PYTHONDONTWRITEBYTECODE=1 python3 integration-v1/run.py "$output"
