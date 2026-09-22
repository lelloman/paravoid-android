# Track C lifecycle handoff

Branch `v1/lifecycle`; isolated worktree `/tmp/paravoid-v1-lifecycle`.
Main checkout unchanged. Shared foundation commits `7ace172` and `d58457f` are
cherry-picked as `486e69d` and `d304816`; do not reapply both copies on integration.

## Implemented

- `RuntimeLifecycle` implements shared `Lifecycle`/`GenerationLease` with a required
  injected `VpkVerifier`. There is no production fake/default archive verifier.
- Durable authenticated time, revision, app-lineage identity and credential-epoch
  admission; signed metadata vectors exercise the real shared verifier boundary.
- Owned immutable archive/component materialization, final admission recheck,
  pending-only publication and reauthentication/rehashing before process loading.
- Atomic selection/trial journal, process-lifetime OS shared leases, cold-boundary
  activation, startup progress/main-frame health and forward-only quarantine/repair.
- Retention protects selected/pending/configured history and every leased generation.
  Preparation and cleanup share a separate OS lock. Slow deletion follows atomic
  removal under selection; no PID/timeout inference is used.

Constructor inputs are installed `ShellPolicy`, process `RequestScope`, mandatory
archive verifier, clock adapter and main-process flag. Store under noBackupFilesDir.
`initializeNew()` is an explicit first-install operation, never error recovery.
A partial initialization fails closed; first-install crash-resume repair is not yet
implemented. `cleanup()` is available to controls and runs before staging.

Lock order is preparation lock (when needed) -> registry monitor -> selection lock
-> generation lease probe. Long preparation/verification/deletion runs outside
selection. Shared channels live for the process; unused exclusive probes close
only the registry's sole descriptor after unlocking. Lock files remain permanent.

## Validation

- `bash lifecycle-tests/run.sh`: host subprocess storage/admission/selection/facade
  tests, including process death, interrupted publication, corruption, credential
  replacement, signed metadata vectors, trials, re-download and protected cleanup.
- `ANDROID_HOME=/home/lelloman/Android/Sdk ./gradlew :paravoid-contract:test
  :paravoid-runtime:testDebugUnitTest --offline --no-daemon`: 12 contract and
  17 existing runtime tests pass.
- `python3 lifecycle-tests/android-probe.py`: **only** dedicated `emulator-5586`,
  AVD `Lifecycle36`. Three ART process groups prove shared lease release after the
  final death, atomic-record interruption/directory force, and 90 concurrent durable
  journal increments. Emulator 36.3.10, API 36.1 x86_64; fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:user/release-keys`.
  AVD data is isolated under `/tmp/paravoid-c-avd`; no phone was targeted.

The Android probe is shell-UID filesystem/ART evidence, not an installed-app,
component lifecycle, application sandbox, or hardware power-loss test. API 30,
physical ARM64 and signed-release acceptance remain outstanding. Fake ZIP fixtures
in LifecycleTest verify ownership/control flow only, not VPK interoperability.

## Remaining integration / questions for A and B

1. Track A's real VPK verifier and executable A/B releases are still needed.
2. Supply installed-policy/descriptor storage, component-kind inventory and recovery
   process/activity routing. Existing ShellApplication/factory entry points remain
   untouched; no competing policy parser, downloader or UI was introduced.
3. Android clock adapter and startup callback wiring must precede user constructors,
   providers/custom factories and frame reporting. Unavailable component adapters
   (especially JobService/foreground service/provider) are still pending.
4. Confirm callers re-read the **currently installed APK** before setCredentialScope.
   No install-epoch parameter exists for C to reject an old process reasserting a
   previously replaced credential. A stale DTO alone cannot prove current installation.
5. Clarify byte repair when a newer offered version has already advanced the lineage
   floor: current admission rejects lower heads, even for an identical previously
   accepted archive. It does not silently add an exception to V1's lower-offer rule.
6. Root initialization recovery, schema migration, disk-full/device-EIO injection,
   app sandbox tests, and unavailable UI/component acceptance are not completed.

See PLAN.md for slice-by-slice implementation notes and earlier limitations that
are superseded by this current handoff.
