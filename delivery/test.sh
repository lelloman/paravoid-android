#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
output=$(mktemp -d /tmp/paravoid-delivery-tests.XXXXXX)
trap 'rm -rf "$output"' EXIT
mapfile -t sources < <(find src test tools/src -name '*.java' -print)
mapfile -t contracts < <(find ../paravoid-contract/src/main/java ../paravoid-recovery-api/src/main/java -name '*.java' -print)
javac --release 11 -Xlint:all -d "$output" "${contracts[@]}" "${sources[@]}"
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.TransportTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.AttemptPolicyTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.DeliveryClientTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.RecoveryProviderTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.ApkGrantReaderTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.ApkPolicyReaderTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.DeliveryControllerTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.DeliveryLocksTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.LegacyDeliveryCleanupTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.RecoveryActionsTest
java --add-modules jdk.jdi -ea -cp "$output" com.lelloman.paravoidandroid.delivery.PersistenceDeathTest
java -ea -cp "$output" com.lelloman.paravoidandroid.delivery.SignedDeliveryTest
DELIVERY_TEST_CLASSES="$output" PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s reference -p 'test_*.py'
DELIVERY_TEST_CLASSES="$output" PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'
