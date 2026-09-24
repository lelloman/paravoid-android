# Complete-shell installed-app fixture (in progress)

This fixture exercises the production `packaging = 'complete'` path, not a
fixture-only loader. It contains an Application, Activity, provider, receiver,
started service and JobService, plus Android resources, assets and Java resources.

Generate disposable local keys and build:

```sh
python3 compatibility/complete-v1/prepare.py
ANDROID_HOME=/path/to/sdk ./gradlew -p compatibility/complete-v1 \
  assembleNormalDebug assembleParavoidAndroidDebug
```

`prepare.py` requires Python cryptography. Generated private keys stay in ignored
`build/keys/`; they are test-only credentials, not publication keys. The fixture
uses opt-in debug HTTP at `http://127.0.0.1:18765/`. Use an explicitly selected
emulator and `adb reverse` when running a local server; do not install on a phone
implicitly. The normal APK and `build/outputs/paravoid/paravoidAndroidDebug/shell.apk`
are separate app IDs.

Build properties: `-Pgeneration=B -PpayloadVersion=2`, `-Pbootstrap=empty`,
`-Pauthentication=apkKey`, and `-Pbaseline` (reads `build/accepted/`). A generation
named `broken` injects an Application startup failure. Promote a reviewed baseline
before testing updates against a fixed shell; different installed contracts are
not interchangeable.

## Evidence at the main-branch integration checkpoint

Both normal and complete APKs built and installed on an isolated API 36.1 x86_64
emulator. The first complete startup failed because `Files.getFileStore` throws
`SecurityException` in Android's app sandbox. The implementation now uses
`File.getUsableSpace` in delivery and materialization, but the subsequent rebuild
was blocked by another Gradle process holding its cache lock. Successful installed
startup after that correction has **not** yet been demonstrated.

This is a committed starting point for the emulator suite, not a passing
end-to-end acceptance test. Remaining work includes automated device assertions,
fixed-shell updates, auth/revocation/replacement, empty/recovery component behavior,
process coordination, forward repair and API 30 coverage.

## Runtime follow-up, 2026-09-22

`device-check.py` builds five disposable shell fixtures and a normal control. It
requires both an explicit emulator serial and the expected AVD name; it refuses
physical devices. It uninstalls/reinstalls **only these fixture packages**, and
uses `run-as` for intentional corruption of their private data. Never point it at
an AVD belonging to another developer. No production fault-injection API is added.

```sh
ANDROID_HOME=/home/lelloman/Android/Sdk \
GRADLE_USER_HOME=/tmp/paravoid-c-finish-gradle \
python3 compatibility/complete-v1/device-check.py --build
python3 compatibility/complete-v1/device-check.py --serial emulator-5590 --avd Runtime36
python3 compatibility/complete-v1/device-check.py --serial emulator-5592 --avd Runtime30
bash lifecycle-tests/run.sh
ANDROID_HOME=/home/lelloman/Android/Sdk \
GRADLE_USER_HOME=/tmp/paravoid-c-finish-gradle \
./gradlew :paravoid-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2
```

Builds use AGP 8.13.2, Gradle 8.13, JDK 21, compile/target SDK 36 and minSdk 30.
Generated APKs/VPKs and build logs stay in ignored `build/device-cases/`. Disposable
keys remain under `build/keys/`; do not run `clean` between builds and tests.

Device suite passed on both dedicated x86_64 emulators, emulator 36.3.10.0:

| Platform | System image fingerprint |
| --- | --- |
| API 36.1 | `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:user/release-keys` |
| API 30 | `google/sdk_gphone_x86_64/generic_x86_64_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys` |

Verified through the real installed complete loader/verifier:

- Normal control; complete embedded provider-before-Application ordering,
  Android resources/assets/Java resources, configuration contexts, first-frame
  health and offline cold reuse of the same generation.
- Background-only start stays trial with completed Application progress; a
  same-UID named worker joins the generation; foreground frame establishes health.
- Empty-shell launcher routes to recovery; receiver and started service execute
  no payload; provider returns no successful rows. Recovery descriptors/mappings
  contain no payload lease or generation files.
- Application failure quarantines immediately and durably; the same embedded
  generation is not automatically retried. Two incomplete cold attempts pause
  further automatic entry.
- A higher embedded repair APK under the **same contract** repairs quarantine.
  This failed before the runtime fix: only INCOMPATIBLE triggered embedded staging.
- Corrupt materialized resources are rejected. An older embedded APK cannot
  bypass the persisted floor; restoring the accepted APK repairs identical bytes.
- Corrupt selection state is preserved, fails closed and still routes to the
  shell-owned repair message. This is not a claim that corrupt security history
  can be repaired without an authorized state-migration design.

The private journal decoder is read-only test evidence, never runtime authority.
The normal fixture's original `InputStream.readAllBytes()` failed on API 30 and
was replaced with bounded-buffer stream copying. Host lifecycle regressions and
17 runtime unit tests also pass; these are separate from the installed tests.

Further device checks (both API 30 and 36.1):

```sh
python3 compatibility/complete-v1/download-check.py --serial emulator-5590 --avd Runtime36
python3 compatibility/complete-v1/download-check.py --serial emulator-5592 --avd Runtime30
python3 compatibility/complete-v1/components-check.py --serial emulator-5590 --avd Runtime36
python3 compatibility/complete-v1/components-check.py --serial emulator-5592 --avd Runtime30
```

The build now promotes A's fixture-only baseline and checks later embedded builds
against it. The download suite imports B's unmodified reference server, signs a
fresh head with the fixture head key, binds a private ephemeral host port and
reverses only the selected emulator's port 18765. It verifies B remains pending
while A runs, a surviving worker prevents activation after main-process death,
and final payload-process death permits cold B activation in the unchanged A
APK. Accepted B then runs offline. API 30's SELinux policy rejects `run-as kill`
against the app; the fixture-only `probe.die` receiver performs abrupt self-death
instead. This is not a production runtime control.

To repeat those checks with the experimental payload R8 pass, build fresh
disposable signed fixtures and run the installed-app and local HTTP update tests:

```sh
python3 compatibility/complete-v1/device-check.py --build --minify
python3 compatibility/complete-v1/device-check.py --serial emulator-5584 --avd ParavoidR8
python3 compatibility/complete-v1/download-check.py --serial emulator-5584 --avd ParavoidR8
```

The fixture's `payload-rules.pro` retains names used by its JNI library. The
minified build saves per-generation mappings in `build/device-cases/` and checks
that R8 obfuscated a payload class. On a fresh API 36.1 x86_64 emulator, both
device suites passed with minified A and B VPKs: the installed A shell downloaded
signed B from the local reference server, activated B after a cold process
restart, and ran B after the server stopped. This is a local test catalog, not
an upload to an external store.

The complete fixture reuses the existing JNI library without modifying it.
Application construction verifies `JNI_OnLoad`, `FindClass` callbacks,
`DT_NEEDED` dependencies, C++ shared runtime and `dlopen` before providers or
Application.onCreate. Device mappings verify loaded native files belong to the
selected generation, including after fixed-shell B activation. The normal control
passes the same native-constructor checks. Toolchain: NDK 27.0.12077973 and CMake
3.22.1; libraries build for x86_64 and arm64-v8a, but **only x86_64 executes here**.

`components-check.py` exercises empty, quarantined and corrupt states. An external
normal APK receives `onNullBinding`; receiver and started-service calls execute no
payload code; a declared `dataSync` foreground service satisfies Android's
deadline using a shell-owned notification before stopping. Provider operations
explicitly throw unavailable, while non-exported authority access remains denied
by Android. An actual scheduled ProbeJob dispatches through the generated
JobService adapter and its scheduler record shows a failure/reschedule rather
than successful payload work. Job dumps are saved in ignored `build/device-cases/`.
All these checks pass on both APIs. No intents, binder calls or jobs are replayed
by the adapters.

Artifact checks also reject installed movable assets, Java resources and native
libraries. The only permitted native shell entries are the exact checked-in
`libparavoid_abi.so` markers used for Android ABI selection, compared byte for
byte with the producer's marker resources.

Existing legacy resource-shell regressions also pass: artifact assembly, normal
and shell lint, and **25 installed device stages on each API**, including main/
worker loading, configuration changes, A/B/A replacement and corrupt/writable
resource and Java-resource cache rejection. Reproduce after the complete tests:

```sh
ANDROID_HOME=/home/lelloman/Android/Sdk \
GRADLE_USER_HOME=/tmp/paravoid-c-finish-gradle PYTHONDONTWRITEBYTECODE=1 \
python3 compatibility/automatic-resources/production-check.py
ANDROID_HOME=/home/lelloman/Android/Sdk ANDROID_SERIAL=emulator-5590 \
PYTHONDONTWRITEBYTECODE=1 python3 compatibility/automatic-resources/production-check.py --device-only
ANDROID_HOME=/home/lelloman/Android/Sdk ANDROID_SERIAL=emulator-5592 \
PYTHONDONTWRITEBYTECODE=1 python3 compatibility/automatic-resources/production-check.py --device-only
```

These existing tests produce JSON evidence under
`compatibility/automatic-resources/build/production/evidence-emulator-*.json`.

The expanded test found that swallowing Application.onCreate failure left its
already-published providers callable. Complete mode now records quarantine and
propagates that startup failure so Android terminates the failed process. The
**next** entry constructs unavailable providers and routes to recovery. The
dedicated test AVD disables crash dialogs for deterministic fault injection; a
real device may show Android's crash UI for that initial failed attempt.

Still pending: recovery controls that guarantee a cold start without manual
force-stop, agreed controller/process ownership and storage reservation, installed
credential replacement/revocation, storage exhaustion, startup custom-factory
and lifecycle-callback failures, foreground types with additional dynamic platform
requirements, and broader app integrations. See
[`lifecycle-tests/COORDINATION.md`](../../lifecycle-tests/COORDINATION.md) for exact
proposals and boundaries. Emulator results do not satisfy physical ARM64,
signed-release app acceptance, or independent security review. Track C is **not
complete** while those gates remain open.
