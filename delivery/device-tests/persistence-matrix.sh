#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^emulator-[0-9]+$ ]]; then
  echo 'Usage: persistence-matrix.sh emulator-SERIAL [HOST_SERVER_PORT]' >&2
  exit 2
fi
device_args=(--serial "$1" --server-port "${2:-18765}")
# Requires the ordinary empty/public A/p1 fixture. Every invocation resets the
# fixture shell's data; only the explicitly selected disposable emulator is used.
python3 -u delivery/device-tests/public_bootstrap.py "${device_args[@]}" --persistence-crash
python3 -u delivery/device-tests/public_bootstrap.py "${device_args[@]}" --persistence-retry-replacement
for boundary in created written synced renamed directory-synced; do
  python3 -u delivery/device-tests/public_bootstrap.py "${device_args[@]}" --persistence-first-cancel "$boundary"
done
