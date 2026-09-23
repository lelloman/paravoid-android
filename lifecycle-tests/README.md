# Track C lifecycle handoff

## Restart decision regression (2026-09-23)

`RestartProcessGateTest` runs in `run.sh` against the production restart decision
class with deterministic platform snapshots and elapsed time. It covers a bounded
five-second timeout, respawn without repeated killing, recovery/foreign-UID
exclusion, missing enumeration, ambiguous ownership, kill denial, stale PID
ownership and a respawn/shared-UID change before the main-thread launch callback.
These are decision tests, not actual Android sticky-service or timeout evidence.

The Android adapter now requires PackageManager to report this package as the
only owner of its UID, re-enumerates ownership before each kill and rechecks before
launch. It never fakes lease release or changes selection to force activation.
There is still no atomic Android enumerate-and-launch operation: a process starting
after the final check is governed by normal lifecycle leases and may delay pending
activation. The restart result means a launch was requested, not that a particular
pending version is guaranteed to have activated.

The full host lifecycle suite and Android A/B fixture builds passed. The installed
fixed-shell pending-B/live-A-worker/confirmed-offline-restart regression also passed
on API 30 and 36.1 (2026-09-23), including recovery survival and a new worker loading
B. B was rebuilt against the new shell baseline; the prior B fixture was correctly
rejected for contract mismatch. These device passes validate the normal Android
adapter path, not the injected host failure scenarios above.

## Pending publication fault regression (2026-09-23)

`bash lifecycle-tests/run.sh` includes `PublicationTest`: a parent holds a healthy
version-1 process lease while a separate JVM stages version 2 through the real
`RuntimeLifecycle` facade. A package-private, no-op-by-default journal fault seam
injects IOException or terminates that JVM at each of three boundaries: temporary
content synced, selection renamed, and parent directory synced (six cases).

Before rename, selection stays byte-identical and cleanup removes the unreferenced
published generation. After rename, the higher version is valid pending state even
when the API reports IO; cleanup must retain it. Every case checks active bytes and
the live lease, unchanged security history, rejection of a lower-version head,
successful retry (including already-pending), and cleanup of duplicate generations.
The full lifecycle and delivery host suites and Android fixture build are the
validation commands for this slice.

These are host filesystem/process tests using explicitly fake VPK evidence, not
Android ENOSPC, signed-fixture, disk-cache power-loss, or every write/fsync boundary
coverage. The seam is not exposed by public runtime constructors or shell settings.

## Original track handoff

Consolidated follow-up: `RuntimeLifecycle.openOrInitialize()` now publishes first
state atomically under a permanent parent lock. A checksummed parent anchor
distinguishes unfinished first setup from an established store that disappeared;
it never resets established replay state. Host subprocess tests cover five crash
boundaries and four simultaneous first launches, plus missing/corrupt established
state. This replaces the old partially-created-directory startup failure; Android
filesystem/device validation is still required.

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

- `InstalledAuthorityTest` replaces the injected installed-APK authority after the
  lifecycle's private archive copy has been verified, before publication. Staging
  must reject the stale credential, preserve active leased bytes and replay floors,
  then accept the same candidate under a freshly admitted replacement grant. This
  is a host facade test with test-only archive evidence, not real PackageManager
  replacement during Android staging.
- `SpaceAdmissionTest` covers the shared update-space claim: cross-JVM exclusion
  and death release, same-JVM descriptor safety, exact budget boundaries, cancellation,
  credential invalidation, protected active/pending bytes and replay floors. Embedded
  extraction input survives ordinary cleanup, is reaped after an abandoned owner,
  and is deleted before release; stale close cannot remove a new owner's input.
  These are host tests with fixture verification and injected capacity, **not**
  emulator disk-exhaustion evidence or physical disk preallocation.
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

## Consolidated follow-up

The branches have been consolidated on `v1/packaging`. `CompleteVpkVerifier` and
`integration-v1/run.sh` now exercise actual Android-built signed A/B content through
delivery and lifecycle on the host; this is not yet installed-app startup evidence.

The stale credential question above is addressed by `InstalledStateSource`: C reads
current authority outside selection, then checks its identity inside admission/
publication. Old non-null and null assertions cannot supersede the actual APK's
scope, and changed shell contracts reject old instances. `InstalledAuthorityTest`
covers replacement and final-check races. The full APK profile requires this source;
Android PackageManager and early-loader wiring remain to be completed.

Byte repair below the lineage floor remains deliberately rejected. Retained accepted
payloads may run offline, but a new download must not bypass the floor; publish a
higher-version forward repair instead.
