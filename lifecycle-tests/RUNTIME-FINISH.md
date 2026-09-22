# Track C runtime checkpoint — remaining gates open

Branch: `v1/runtime-finish`. Worktree: `/tmp/paravoid-v1-runtime-finish`.
Base: `74256422648a790f8dac8716a844d65d4003f3d5` on main.
No other developer worktree, phone, publication service or remote branch changed.
No shared commits imported after this baseline; integration should not reapply
the already-consolidated lifecycle or shared-contract commits.

## Changes and evidence

1. `3281e6e`: embedded forward repair under the same installed contract;
   installed startup/recovery/fault suite on API 30 and 36.1.
2. Subsequent runtime/component slice: declared foreground-service shutdown
   satisfies the foreground deadline with a shell notification; failed
   Application.onCreate propagates after quarantine so already-published providers
   cannot remain callable. Expanded fixture verifies real HTTP pending/cold
   selection, live worker leases, native loading and actual unavailable components.

Exact build/device commands, fingerprints and failure-injection instructions are
in [the owned fixture README](../compatibility/complete-v1/README.md). All three
complete fixture scripts pass on both isolated x86_64 emulators. Native constructor
checks use the existing JNI module, with no edits to its sources. There is no
fake verifier, alternative journal or production fault-injection switch.

Host checks:

```sh
bash lifecycle-tests/run.sh
ANDROID_HOME=/home/lelloman/Android/Sdk \
GRADLE_USER_HOME=/tmp/paravoid-c-finish-gradle \
./gradlew :paravoid-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2
git diff --check
```

The host lifecycle suite and 17 runtime unit tests pass. The existing legacy
production resource-shell artifact/lint check and 25 installed stages on each
API also pass. No new claim is made about the separate DEX-only matrix.

## Remaining work

The track is **not complete**. [Coordination proposals](COORDINATION.md) describe
the required delivery ownership, actual cold-start recovery action, storage
reservation and startup callback boundary. Download tests explicitly inject
process death; they do not prove that the current recovery UI can produce a cold
start without adb. Application failures now terminate safely, but initial failure
may show Android's crash UI; the next entry routes to shell recovery.

Installed credential replacement/revocation, disk exhaustion, broader custom
factory/lifecycle failures and foreground types needing additional dynamic Android
authorization remain unverified. API 30/36.1 emulator results are not physical
ARM64, real-app, release-signing or independent security-review acceptance.

The isolated AVDs are Runtime36 (`emulator-5590`) and Runtime30
(`emulator-5592`), created under `/tmp/paravoid-c-finish-avd`. Gradle home is
`/tmp/paravoid-c-finish-gradle`; API 30 image is under
`/tmp/paravoid-c-api30-image`. AVDs are stopped at handoff, not deleted. Generated
fixture keys/APKs/VPKs remain ignored under the worktree for reproducibility.
