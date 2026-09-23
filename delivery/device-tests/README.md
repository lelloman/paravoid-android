# Production delivery device tests

Use **dedicated disposable emulators**, with explicit serials. Scripts install and
clear only the complete fixture app, install the normal control, configure/remove
`adb reverse tcp:18765`, and press real shell UI controls. Do not use another
track's emulator. Run scripts sequentially: the fixture pins port 18765.
Do not rebuild fixtures while a script is serving their VPK files.

The auth script also accepts `--server-port PORT`: it binds a different host port
and reverses the device's pinned 18765 endpoint to that port. This permits separate
auth suites on separate disposable emulators with immutable shared build inputs;
each suite keeps its own catalog, grants and temporary personalized APKs.

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

The auth suite also checks recovery-to-main suppression with an unexpired grant
and an eligible automatic check. It ages **only the non-security scheduling
timestamp** while the app is stopped; it does not advance device time or change
grant/denial/replay state. Main must update its scheduling timestamp without issuing
HTTP. A launcher-shortcut explicit retry then restores access. Finally, main alone
starts an archive request whose response is held by the server. Recovery opens via
the real shortcut and cancels it: the server must observe socket EOF before serving
the response, no pending payload may appear, and main must survive. These assertions
must not be described as seven hours of real elapsed-time testing or cancellation
after staging handoff.

`lock_probe.py --serial emulator-NNNN --avd ExpectedName` compiles the production
delivery lock primitive plus its probe for ART. For both attempt and transfer
paths, repeated failed same-VM claims must leave another ART process excluded;
normal close and owner death must permit a fresh claim. This is shell-UID primitive
evidence, not app-component or HTTP testing. It uses and deletes only a uniquely
named emulator scratch directory. The installed auth suite supplies separate
app-sandbox/process/HTTP evidence.

Both gates passed on 2026-09-23 with implementation `6f1e8de`: Restart30
(`emulator-5586`, API 30) and Restart36 (`emulator-5584`, API 36.1), x86_64.
The installed auth suite also revalidated unusable grants, persistent 403,
signature-preserving APK credential replacement during outstanding HTTP and
offline execution after revocation. API 36 initially exposed a test-driver HOME
transition race; the shortcut driver now waits and retries drawer opening before
asserting icon presence, and the entire auth suite passed on rerun.
Credential replacement during staging, cancellation at retry
persistence boundaries, metering transitions and real HTTPS remain separate gates.

Add `--retry-replacement` to `apk_key.py` to exercise real APK replacement while
recovery holds a persisted HTTP 429 retry with `Retry-After: 3600`. The test verifies
the future due time, revokes that credential, installs a differently personalized
APK with the same developer signatures, and launches main. It must discard the old
partition's delay immediately, issue head/archive requests only with the currently
installed credential, remove stale retry state and leave the active payload usable.
Neither the retry file nor device clock is edited for this step. This covers
replacement during a scheduled retry, not replacement halfway through staging or
process death at every retry-write boundary.

Passed on 2026-09-23 against final implementation `5a7dcdc` (including `5acf2ab`):
Restart30/API 30 (`emulator-5586`, host port 18765) and Restart36/API 36.1
(`emulator-5584`, host port 18766), both x86_64. Each ran the entire auth suite with
`--retry-replacement`, revalidating cross-process cancellation and suppression,
explicit retry, HTTP-time APK replacement and the new persisted-delay replacement.
The final-build runs passed after the checkpoint/monitor lock-order correction;
both emulators were stopped afterward. No phone or publication was involved.

## Retry process-death and verified-staging replacement gates

`apk_key.py --retry-crash` adds a deterministic process-death test. A real HTTP 429
starts the retry cycle; each subsequent head response is held while the script
checks the persisted consumed-retry counter (2, 3, then 4), backgrounds the UI,
and sends SIGKILL to the fixture's resolved main/recovery PIDs. Each fresh launch
must consume the next retry, never reset the budget. After the third interrupted
retry, a restart must clear exhausted retry state without another HTTP request;
the active payload and an explicit new retry must still work. No retry records or
clocks are edited. This covers death after durable retry consumption, **not** every
instruction around temporary-file rename, power loss, or filesystem corruption.

The API 30 Google APIs image requires `adb -s SERIAL root` **before** this crash
suite: signals from `run-as` to app processes were denied on this image. Its toybox
kill applet also misparses numeric signals under `run-as`, so the driver uses the
shell builtin. API 36.1 uses unprivileged `run-as` signal injection. These are test
harness requirements on disposable emulators, never production app permissions.

`apk_key.py --staging-replacement-only` runs a separate, shorter staging test.
It requires a JDK with `jdk.jdi` and a debuggable fixture APK. The test-only
`StagingGate.java` attaches over a temporary adb JDWP forward and suspends only the
delivery thread at normal return from the production `verifyDownloaded` method.
It checks the private archive's hash and absence of published generations, then
installs an APK carrying a replacement grant. The old VM must disconnect, the
security record must remain byte-identical, and no old generation may be published.
A fresh process must use only the new credential, clean abandoned staging, stage
successfully and launch the payload. No production fault hooks or verifier bypasses
are added, and debugger forwards are removed afterward.

This staging gate starts from an empty shell and uses normal base-APK replacement,
which kills old processes. It is not evidence for a surviving stale process, an
already-active generation under staging interruption, or a non-debuggable release
APK. Host authority/lease tests cover separate logic boundaries.

Both new gates passed on 2026-09-23 on Restart30/API 30 (`emulator-5586`) and
Restart36/API 36.1 (`emulator-5584`), x86_64, with the production implementation
unchanged from `5a7dcdc`. Retry runs included `--retry-crash --retry-replacement`;
the staging gate ran separately with `--staging-replacement-only`. The complete
host delivery suite also passed. API 30 adb root was used only for crash injection
and restored afterward; both emulators were stopped. No phone or publication.

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
