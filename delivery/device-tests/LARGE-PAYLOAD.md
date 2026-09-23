# Near-limit valid payload acceptance

This is an actual Android application with a 1,000 MiB incompressible asset, not a
sparse-file limit probe. The normal control and the downloaded payload each read
every byte through Android AssetManager using a bounded buffer and must match the
host's byte count and SHA-256. The VPK goes through production signature/inventory
verification, materialization and confirmed offline restart.

## Reproduction

Use disposable API 30/36.1 emulators, Python 3.11+ and the usual fixture build setup:

```sh
python3 compatibility/complete-v1/prepare.py --pressure-mib 1000
gradle -p compatibility/complete-v1 assembleNormalDebug assembleParavoidAndroidDebug \
  packageParavoidAndroidDebugParavoidVpk \
  -Pbootstrap=empty -Pauthentication=public -Pgeneration=A -PpayloadVersion=1 -PstartupFault=none
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5586 --large-payload --restart-controls
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5584 --server-port 18766 --large-payload --restart-controls
```

Allow several GiB of free device storage and substantial host space for Android
build intermediates. The fixture uses its usual 2 GiB Gradle heap. The harness
clears prior fixture shell data and uninstalls the normal control after hashing
its asset, before downloading into the independent shell package. This prevents
the normal control from consuming the update's storage reservation.

This case tests one near-limit update, not repeated staging of another such update
on a 6 GiB emulator. A download reservation is `3 * archiveSize + 64 MiB` in
addition to protected existing data; low-space refusal remains correct even if
an earlier pending release is already usable. Smaller fixtures separately test
repeated checks, retries and preference controls.

Restore ordinary fixtures with `prepare.py --pressure-mib 0` and rebuild. This
deletes only the generated padding asset. Android's incremental APK writer can
retain large unused gaps after asset removal: use a fresh normal APK packaging
output if a compact normal control is required. Do not ship these test artifacts.

## Results and discovered fix

Passed 2026-09-23 on Restart30/API 30 and Restart36/API 36.1 x86_64:
`/tmp/paravoid-large-accepted30.log`, `/tmp/paravoid-large-accepted36.log`.
The VPK was **1,051,566,360 bytes** and the empty shell **369,242 bytes**.
VPK SHA-256: `aefbf2bae9c150cab822d62758baca42f3ca340f37becaa17bd15ac39421fac6`.
Both normal controls and both downloaded apps hashed all 1,048,576,000 asset bytes.
Cancelling restart preserved main; confirming replaced main, preserved recovery
and executed the payload with the server stopped.

The experiment found a legacy 256 MiB embedded-archive cap incorrectly applied to
complete packaging. `d7f7072` keeps that cap for the legacy profile and lets the
complete VPK producer/verifier enforce complete-mode bounds. Build evidence:
`/tmp/paravoid-large-fixed-build.log`; legacy boundary unit regressions pass.

An earlier generated input capture differed from the valid Android-built APK by
one byte and was correctly rejected by signature verification. Regenerating that
capture succeeded; the cause of the mismatch was not established. The suspect
artifact is retained at `/tmp/paravoid-large-corrupt-capture.1KnhDM/input.apk`.
This is not evidence of a diagnosed/fixed copy bug. Other early harness failures
were release/debug signer mismatch, retained fixture storage and an immediate
READY assertion during asynchronous work; final passing scope is stated above.

The test is near the 1 GiB archive cap, not execution of an exactly 1 GiB archive,
physical ARM64 evidence or proof that every resource/container layout is valid.
Exact size/entry/name/envelope rejection boundaries are covered separately by
`ArchiveLimitsTest`.
