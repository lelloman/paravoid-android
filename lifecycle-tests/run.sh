#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d /tmp/paravoid-lifecycle-classes.XXXXXX)
trap 'rm -rf "$out"' EXIT
javac --release 11 -d "$out" paravoid-contract/src/main/java/com/lelloman/paravoidandroid/contract/*.java paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/*.java lifecycle-tests/*.java
java -cp "$out" com.lelloman.paravoidandroid.runtime.lifecycle.StorageTest
java -cp "$out" com.lelloman.paravoidandroid.runtime.lifecycle.AdmissionTest
java -cp "$out" com.lelloman.paravoidandroid.runtime.lifecycle.SelectionTest
