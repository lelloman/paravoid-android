# Production delivery device tests

Use **dedicated disposable emulators**, with explicit serials. Scripts install and
clear only the complete fixture app, install the normal control, configure/remove
`adb reverse tcp:18765`, and press real shell UI controls. Do not use another
track's emulator. Run scripts sequentially: the fixture pins port 18765.
Do not rebuild fixtures while a script is serving their VPK files.

Requirements: JDK, Gradle's cached Android dependencies, Python `cryptography`, adb,
Android build-tools 36.0.0/platform 36, running API 30 and 36.1 x86_64 emulators.
The scripts require an `emulator-NNNN` serial and time out individual adb commands.
Prepare keys only in this worktree's ignored fixture build directory.

From the repository root:

```sh
python3 compatibility/complete-v1/prepare.py
ANDROID_HOME=/home/lelloman/Android/Sdk ./gradlew -p compatibility/complete-v1 \
  assembleNormalDebug assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk \
  -Pbootstrap=empty -Pauthentication=public --console=plain -Dorg.gradle.vfs.watch=false
python3 -u delivery/device-tests/public_bootstrap.py --serial emulator-5582
python3 -u delivery/device-tests/public_bootstrap.py --serial emulator-5580

ANDROID_HOME=/home/lelloman/Android/Sdk ./gradlew -p compatibility/complete-v1 \
  assembleNormalDebug assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk \
  -Pbootstrap=empty -Pauthentication=apkKey --console=plain -Dorg.gradle.vfs.watch=false
python3 -u delivery/device-tests/apk_key.py --serial emulator-5582
python3 -u delivery/device-tests/apk_key.py --serial emulator-5580
```

The explicit VPK task matters: an empty-shell assembly intentionally does not
need an embedded VPK; a stale VPK from a different authentication/bootstrap policy
cannot be served under its new contract. Both scripts check policy/manifest match.

For the user-facing activation path, pass `--restart-controls` to
`public_bootstrap.py`. After real download/staging and shutting the server down,
it cancels the restart confirmation and checks the original main PID is preserved,
then confirms restart and checks a new main PID executes the downloaded payload
while the recovery PID survives. It does not substitute adb force-stop for this
activation. Preference checks await the asynchronous disk write with a bounded
timeout. This mode passed on dedicated Restart30/API 30 and Restart36/API 36.1
emulators on 2026-09-23. These results cover empty-bootstrap activation, not all
worker, quarantine, timeout or shared-UID cases.

For real free-space admission, add `--storage-pressure --restart-controls`.
The script allocates a named app-private filler with actual writes (not sparse
growth), leaving about 48 MiB free. It requires `INSUFFICIENT_STORAGE` with no
current/pending generation, shrinks the filler to restore about 512 MiB free, and
retries through the UI. The signed download and materialization must succeed below
600 MiB free, followed by the ordinary controls and offline activation checks.
The filler is removed in `finally`; use only a disposable emulator with several
GiB available on its host filesystem. This intentionally pressures its whole data
partition. Individual allocation commands allow up to four minutes.

This gate covers low-space preflight and the tighter budget, not mid-write ENOSPC,
an already-active app under disk exhaustion, physical ARM64 or competing app
processes. Those remain separate acceptance tests; host injection is not a substitute.

Passed on 2026-09-23 using `--storage-pressure --restart-controls`: disposable
Restart30 (`emulator-5586`, API 30) and Restart36 (`emulator-5584`, API 36.1), both
x86_64, with the runtime/storage implementation at `79f5caf`. Both also passed
preference persistence, explicit check/retry, offline cancellation, cancelled
restart confirmation and confirmed offline payload activation. Filler deletion
was verified on each emulator before shutdown. No phone or publication was involved.

`public_bootstrap.py` verifies normal launch, immediate production empty bootstrap,
authenticated head and archive staging through the production verifier/lifecycle,
current versus pending state, actual check/retry/cancel controls, persisted
check/download/unmetered settings, retention selection, and offline cold activation.
Metered-network enforcement itself is host-tested; this script tests the real
setting and explicit download override, not Android network policy transitions.

`apk_key.py` verifies developer signatures on temporary personalized APKs, then
installs missing, invalid-signature, expired and valid-grant variants. Unusable
grants must issue zero HTTP requests, including explicit retry. A server 403 must
persist across process restart. Explicit retry starts a held archive request;
package replacement supplies a new grant while that request is outstanding.
A newly authorized download must stage, and revocation must preserve offline use.
The observer records only whether Authorization was present, never its contents.

The auth test uses the production carrier insertion primitive to create private
debug-HTTP test APKs, including intentionally invalid inputs. This is **not** proof
of the production HTTPS-only personalization CLI on-device; its strict verified
CLI path has separate host APK-signature/readback tests. The production Android
policy/grant/HTTP/head/VPK/lifecycle code runs without verifier mocks or bypasses.

## Recorded devices, 2026-09-22

- `emulator-5580`, disposable `/tmp/paravoid-b-avds/Delivery36.avd`:
  API 36.1 Google Play x86_64 image revision 4, extension level 20.
- `emulator-5582`, disposable `/tmp/paravoid-b-avds/Delivery30.avd`:
  API 30 Google APIs x86_64 image revision 16.
- Android emulator 36.3.10.0, build 14472402, headless, no snapshots,
  `-gpu swiftshader_indirect`, two virtual CPUs and 2 GiB RAM.
- API 30 archive obtained from Google's SDK repository:
  `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-30_r16.zip`;
  SHA-1 matched repository manifest `6ae21030eaadc041078444d3798e4b399f3e787d`.
  System image and all AVD data were placed under `/tmp`; existing AVDs were not run.

Both auth suites passed. Public suite results and remaining integration limitations
are recorded in [the finish handoff](../FINISH-HANDOFF.md). This evidence covers
empty-to-downloaded production delivery, not the complete V1 acceptance matrix.
