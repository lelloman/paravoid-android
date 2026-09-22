#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
output=$(mktemp -d /tmp/paravoid-delivery-tests.XXXXXX)
trap 'rm -rf "$output"' EXIT
mapfile -t sources < <(find src test -name '*.java' -print)
javac --release 11 -Xlint:all -d "$output" "${sources[@]}"
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.TransportTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.AttemptPolicyTest
DELIVERY_TEST_CLASSES="$output" PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s reference -p 'test_*.py'
