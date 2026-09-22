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
