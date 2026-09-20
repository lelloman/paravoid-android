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

- OS: FileProvider/cold provider, AndroidX activity-result success/cancellation
  with caller process death, Binder binding-Intent Parcelables, explicit-loader
  nested Bundles, callbacks and service-death recovery.
- Language: generated Parcelable/Serializable saved state, serialization,
  reflection, ServiceLoader and proxy checks across process death.
- Views: ViewBinding/DataBinding, custom XML Views, fragments, saved state and
  configuration contexts.

Initial execution is pending; findings will be recorded here after verification.

## Explicit omissions

- JNI payload native-library lookup requires API 29+.
- Independent resource-pack switching uses public API 30+ ResourcesLoader.
- Notification and foreground drivers currently target API 33+/34+ permission
  and service-type policies. Those drivers are not run here; notifications and
  foreground Services themselves are not unsupported merely because API 28 is old.
- Compose/Hilt, Room/WorkManager, networking, sample instrumentation, release
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
