#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d /tmp/paravoid-lifecycle-classes.XXXXXX)
trap 'rm -rf "$out"' EXIT
javac --release 11 -d "$out" paravoid-runtime/src/main/java/com/lelloman/paravoidandroid/runtime/lifecycle/*.java lifecycle-tests/StorageTest.java
java -cp "$out" com.lelloman.paravoidandroid.runtime.lifecycle.StorageTest
