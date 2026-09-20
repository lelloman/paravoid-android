# API 28 baseline

This is a selected compatibility baseline, not a claim that the whole matrix
has passed on Android 9. It runs the same debug production APKs (target SDK 36)
used by the newer-device probes; it does not lower target SDK or inject test
dependencies into the shell's parent loader.

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/api28-check.sh
```

Use a dedicated, booted, unlocked API 28 emulator. The script verifies the serial
is an emulator and its API is exactly 28, installs/clears fixture packages, and
fails on the first failed probe. Python 3, adb, JDK and the normal build toolchain
are required. Device provisioning is separate from the runner.

Provisioned for this experiment: Google APIs x86_64 API 28 system image revision
11, emulator 36.3.10, 2 GiB RAM, 6 GiB data, 1080×1920/density 420, English UI.
The image is isolated in a temporary directory, not substituted for any existing
user AVD. Its official archive is `google_apis/x86_64-28_r11.zip`, SHA-1
`1d93bd994e29c4e9bbe71fd9c278addc5418cbee`, checked against Google's SDK repository
metadata. There is no APK publication or physical-device testing in this run.

## Selected checks

Verified on the image above, normal **and** shell packaging:

| Probe | API 28 result |
| --- | --- |
| FileProvider / cross-UID access / cold provider | 40 assertions pass |
| AndroidX activity results | All 8 success/cancel × alive/dead-caller scenarios pass; saved-state envelope controls also pass |
| Binder / AIDL | Cold bind, explicit rebind and automatic death recovery pass; binding Parcelables and both nested-Bundle controls pass at every connection |
| Language / discovery | 64 assertions pass, including generated Parcelable and Serializable process-death restoration |
| Views / fragments | 170 assertions plus lifecycle identity checks pass |
| Compose / Navigation / Hilt | Normal passes rotation/navigation/process death; shell startup fails the API 29+ native-library guard because the APK includes `libandroidx.graphics.path.so` |
| Room / WorkManager | Both modes pass generated DAO, persisted work, cold JobService/Worker and committed write verification after two process deaths |

Host regression suite: 58 tests and plugin validation pass; OS fixture lint passes.
With the fix, API 36.1 also passed all 8 result scenarios, 64 language assertions
and 170 Views assertions plus lifecycle identity checks again.
These are debug x86_64 results, not a full API/ABI or release-support declaration.

- OS: FileProvider/cold provider, AndroidX activity-result success/cancellation
  with caller process death, Binder binding-Intent Parcelables, explicit-loader
  nested Bundles, callbacks and service-death recovery.
- Language: generated Parcelable/Serializable saved state, serialization,
  reflection, ServiceLoader and proxy checks across process death.
- Views: ViewBinding/DataBinding, custom XML Views, fragments, saved state and
  configuration contexts.

Initial execution found a driver portability issue: `am start -W` timed out for
the short-lived FileProvider peer after its result was already persisted. The
driver now launches without the draw wait and retains its bounded run-token/PID
completion checks. This was reproduced in normal packaging, not a shell failure.
The next run passed all 40 FileProvider/provider checks and the normal result
cases, but exposed a shell process-death failure: Android 9's
`Activity.performCreate` calls `restoreHasCurrentPermissionRequest` before user
`onCreate`. Reading its Boolean eagerly unmarshals the entire saved-state root,
including payload-only `ProbeParcel`, using the installed shell loader. The
existing pre-`onCreate` repair is therefore too late.

The fix nests callback state in a platform Bundle at each normal return from the
nearest `onSaveInstanceState` override (including inherited/final and persistable
forms). Framework state appended afterward remains at the root. Before app
`onCreate`, the existing hook sets the nested payload loader and restores the
original root contents in place, with later framework entries taking precedence.
This uses public Bundle APIs, not hidden ActivityThread/Instrumentation hooks or
a whitelist of internal Android state keys. The reserved envelope key is
`com.lelloman.paravoidandroid.runtime.PAYLOAD_STATE_V1`.

The result fixture additionally serializes an envelope, reads a late-added Boolean
with a loader unable to see app classes, then verifies payload decoding, nested
save callbacks, repeated preparation and preservation of later entries. Existing
state from before this fix is not a migration guarantee; on API 28, old raw
payload values can still fail before the repair callback. Payload-version changes
and pending runtime permission dialogs remain separate coverage.

## Explicit omissions

- JNI payload native-library lookup requires API 29+.
- Independent resource-pack switching uses public API 30+ ResourcesLoader.
- Notification and foreground drivers currently target API 33+/34+ permission
  and service-type policies. Those drivers are not run here; notifications and
  foreground Services themselves are not unsupported merely because API 28 is old.
- Compose/Hilt was attempted: the pinned Compose graph includes a transitive native
  library, so it is outside the supported API 28 code-only shell subset. The normal
  control passes; shell Hilt/navigation/state behavior cannot be evaluated on this
  image because initialization is rejected first. The driver remains a failing
  reproducer on API 28; native dependency removal or bypass is not a validated fix.
- Networking, sample instrumentation, release
  APK/AAB installation, other ABIs and physical devices are outside this selected
  first pass. Their newer-device results must not be relabeled as API 28 evidence.

## Test architecture

Most compatibility probes are host-driven Android integration tests: build and
install normal/shell APKs, interact through ADB/UI, observe unique per-run reports
and verify real OS component/process behavior. Separate peer APKs provide actual
cross-UID interactions. These scripts are repeatable automation, not manual smoke
tests. This avoids target-side instrumentation making payload classes visible
through the parent loader and accidentally hiding isolation failures.

The repository also has host JVM tests for packaging/transformers/runtime helpers
and Android instrumentation tests in the sample/Hilt fixtures. The different
layers complement each other; not every emulator test is an instrumentation test.
