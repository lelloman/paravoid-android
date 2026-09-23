# Finish integration

## Installed restart polling timeout

After ordinary empty/public A bootstrap, run:

```sh
python3 integration-v1/controls-shortcut.py --serial emulator-5586 \
  --restart-worker --restart-timeout
```

This requires a JDK with `jdk.jdi` and the debuggable fixture. A temporary JDWP
connection to recovery pauses only the restart thread after its initial kill pass,
before starting the real five-second polling clock. The original main and worker
must disappear; the harness then makes one real worker-provider request to model
a competing background entry. The new worker must load A with a different PID.
The thread resumes with real elapsed time and real ActivityManager snapshots.
Debugger breakpoints witness repeated polling and the false-return boundary after
at least 4.9 seconds; no clock, process list, kill operation or result is replaced.

Timeout must display refusal with restart re-enabled, retain the worker and
recovery, leave main absent, and preserve selection/security hashes. After debugger
detach, a second user-confirmed restart must stop the worker and launch A normally,
without adb kill/force-stop or journal edits. The forward/debugger are removed in
`finally`. This is a deliberately scheduled process-creation race on installed
Android, not natural sticky respawn or an unkillable kernel process. It does not
prove a hard deadline for blocked platform calls or pending-update activation.

Passed on 2026-09-23 with unchanged runtime `b28ee03`: Restart30/API 30 observed
49 polls over 5023 ms; Restart36/API 36.1 observed 50 polls over 5094 ms. Both kept
recovery usable and records unchanged, then completed the debugger-free retry.
Public bootstrap passed on both; Java 11 helper compilation and Python syntax
checks passed. No phone, root access or artifact publication was involved.

## Shared-UID restart refusal

Build the complete fixture's normal APK, empty/public shell and VPK with
`-PsharedUidProbe -Pgeneration=A -PpayloadVersion=1`. This opt-in debug manifest
overlay gives the normal and shell APKs the same legacy shared UID; both use the
same debug signing identity. It is a negative restart fixture, not a recommendation
to use shared UIDs or a claim of general shared-UID support.

Use disposable emulators. The ordinary fixture packages must first be uninstalled
because changing an installed package's UID is not an APK update. This deletes only
test data for `com.lelloman.paravoidcompat.complete` and its `.paravoid` counterpart.
Run the ordinary public bootstrap, then:

```sh
python3 integration-v1/controls-shortcut.py --serial emulator-5586 \
  --restart-worker --shared-uid
```

The normal control APK acts as the peer package. Setup force-stops it **before**
starting the test main/worker because a shared-UID force-stop can affect both apps.
PackageManager's installed package UIDs must match. With the peer dormant, then
with its actual process running, user-confirmed restart must show the safe refusal
message and re-enable the restart button. Main, worker, recovery, live peer PID,
selection and security records must all remain unchanged. No synthetic ownership
list, debugger substitution, root or kill is used during the assertions.

Afterward uninstall these two fixture packages, rebuild without `-PsharedUidProbe`
and reinstall if needed. Keep the shared-UID outputs separate from ordinary update
baselines. This test does not cover a deliberately stuck-process timeout or all
legacy shared-UID package arrangements.

Passed on 2026-09-23 on Restart30/API 30 and Restart36/API 36.1, x86_64, with the
unchanged runtime at `97eaf1f`. Both dormant and live peer cases passed with actual
matching package UIDs. A prior API 36 launcher-navigation attempt failed before
restart assertions; a fresh shortcut run passed. The UI harness compares button
labels case-insensitively because Android renders them uppercase on these images.
Both fixture packages were uninstalled afterward and ordinary outputs rebuilt.

## Foreground sticky-worker restart

After building and bootstrapping the current public empty A/version 1 fixture:

```sh
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5586
python3 integration-v1/controls-shortcut.py --serial emulator-5586 \
  --restart-worker --sticky-worker
```

This variant starts the fixture worker as a `dataSync` foreground service and
returns `START_STICKY`. The fixture records its PID, generation and whether Android
delivered a null Intent on recreation, after successfully calling `startForeground`.
The test opens the real recovery shortcut, cancels restart (selection and both PIDs
must survive), then confirms it. It never calls startservice or a provider to induce
the replacement worker. Natural recreation with a new PID and null Intent is
required within 60 seconds; absence is a test failure, not a pass or implicit skip.

Both safe outcomes are accepted: a fresh A Activity launches, or the restart guard
refuses because a worker is present and recovery offers an enabled retry button.
Recovery must survive, and the replacement worker must remain alive for another
six seconds without a repeated kill loop. Finally the fixture service is explicitly
stopped. The harness accepts `am stopservice` exit 255 only with a recognized
response and verifies service disappearance through `dumpsys`; these images report
255 even when stopping succeeds. No adb kill/force-stop is used during this
restart scenario.

Without `--pending-update`, this does not prove higher-version activation during
sticky respawn. Neither variant proves every OS restart-backoff policy, a deliberately
stuck-process timeout, or shared-UID behavior. Worker foreground-service type is
part of the shell manifest; rebuild the fixture shell/baseline when adopting it.

Passed on 2026-09-23 on Restart30/API 30 and Restart36/API 36.1, x86_64, using the
unchanged runtime at `fd313a6`. Both runs observed a fresh A Activity and natural
foreground worker recreation with a null Intent; neither exercised the refusal
branch. The normal/public fixture builds and bootstrap runs passed as well.
No phone, root access or publication was involved.

## Fixed-shell pending update with a live worker

Add `--sticky-worker` to the pending-update command below to require **both** the
fresh Activity and a naturally respawned foreground sticky worker to execute B.
The signed update is staged while A remains alive, then the reference server is
shut down before restart. Cancellation preserves selection and original PIDs.
The replacement worker must receive a framework null Intent, with no service-start
or provider request to wake it. It must survive another six seconds, recovery must
remain alive and the installed shell APK hash must remain unchanged. A safe refusal
or continued execution of A does **not** count as a passing B-activation run.
Service cleanup is verified afterward, as in the standalone sticky test.

The combined `--sticky-worker --pending-update` variant passed on 2026-09-23 on
Restart30/API 30 and Restart36/API 36.1, x86_64, with runtime `b901d48` unchanged.
Both observed B in the Activity and the naturally recreated null-Intent worker;
the reference server was stopped before activation and the installed APK hash
was unchanged. A/B builds and baseline validation passed. These are observed
successful interleavings, not proof of every respawn timing or a timeout branch.

`controls-shortcut.py --restart-worker --pending-update PATH --serial SERIAL`
extends the runnable-app shortcut test. Start from the ordinary public empty-shell
generation A/version 1 fixture after `public_bootstrap.py` completes. `PATH` must
contain immutable `payload.vpk` and `release.json` outputs for generation B/version
2, built against A's promoted baseline with the same throwaway signing keys.

Preparation (use the complete fixture's Gradle invocation and cached SDK setup):

1. Build A/version 1 with `-Pbootstrap=empty -Pauthentication=public` and the normal
   APK, shell APK and `packageParavoidAndroidDebugParavoidVpk` tasks.
2. Promote `build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/` into
   `build/accepted/paravoidAndroidDebug/` in that fixture's ignored build directory.
3. Build B with `-Pgeneration=B -PpayloadVersion=2 -Pbaseline` and the same policy;
   copy its `payload.vpk` and `release.json` into a fresh `mktemp -d` directory.
4. Rebuild A/version 1, then run `public_bootstrap.py` and the shortcut test:

```sh
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5586
python3 integration-v1/controls-shortcut.py --serial emulator-5586 \
  --restart-worker --pending-update /absolute/path/to/saved-B-output
```

Separate emulators can both use immutable outputs with distinct `--server-port`
values (supported by both scripts). Never rebuild while a server serves outputs.
No B shell is installed. The test reads policy from the actual installed APK,
checks B's contract, signs a fresh head with fixture keys, and stages B over the
reference HTTP server while A's main and non-sticky worker remain alive. Cancelling
restart must preserve the selection hash and both PIDs. The server is then stopped;
confirmed restart must launch B offline, stop the old worker and preserve recovery.
A new worker-provider query must execute B, and the installed APK hash must remain
unchanged. There is no adb force-stop or kill in this update/restart test.

This covers a non-sticky started service holding a generation lease, not sticky
respawn, foreground-service behavior, shared UIDs, restart timeout or power loss.

Passed on 2026-09-23 on Restart30/API 30 (`emulator-5586`) and Restart36/API 36.1
(`emulator-5584`), x86_64, with production runtime `de27c65`. Both complete fixture
builds and B's promoted-baseline check passed. No runtime fix was needed; no phone,
root access, APK publication or production fault hook was used.

## Original integration handoff

Worktree `/tmp/paravoid-v1-integration-finish`, branch `v1/integration-finish`.
Starts from Track A `da45412`, imports B `6208140`, `4b72682`, `f06cc5d`,
`00a321f`, `bb8fb17`, `3a4610d`. B's `00a321f` is C's `3281e6e`; skip that
duplicate when importing subsequent C work. C's final `5ab835d` is now imported
as `d76bade`; both original developer worktrees remain untouched.

## Cross-process delivery

- Runtime uses one `paravoid-delivery/shared-v1` directory, serialized by the
  existing OS transfer lock. Authentication denial is shared, not process-local.
  Explicit retry may clear denial only after acquiring that lock and validating
  the current credential partition. Previously partitioned partials are not reused.
- Foreground bootstrap priority is derived from the lifecycle's actual EMPTY
  snapshot, not from being in the recovery process.
- A shared atomic cancellation epoch invalidates existing attempts, including
  persisted retries. A process-local daemon observes it every 100 ms independently
  of the potentially blocked HTTP worker and disconnects that attempt. New explicit
  attempts capture the new epoch. Once handed to lifecycle, staging remains
  synchronous and non-cancellable; no borrowed archive is unlinked early.

Host tests cover shared auth denial beyond the six-hour throttle, explicit retry,
busy retry not clearing denial, recovery throttling, cancellation from another JVM
during blocked archive reads and retry delays, and cancellation of a saved retry
whose owner is gone. These are not Android device claims.

## Controls route

Complete shells register the reserved dynamic launcher shortcut `paravoid.updates`.
Long-press the app icon and select **App updates**. It targets the non-exported
recovery Activity directly; the launcher grants access via Android's shortcut
mechanism, rather than exposing the Activity or loading a payload Activity first.
It preserves other shortcuts using addDynamicShortcuts. A launcher supporting
dynamic shortcuts is required; downstream apps must not remove this reserved ID
or exhaust its shortcut quota. Registration/rate-limit failures do not quarantine
the app.

The controls now also offer an explicitly confirmed **Restart app** action. It
warns about loss of ongoing/unsaved work, stops only exclusively owned same-UID
app processes other than recovery, waits for their disappearance and launches a
fresh launcher Intent. It never clears journal state or releases leases early.
Shared-UID ambiguity and inability to establish a cold boundary fail closed.
On 2026-09-23, confirmed restart passed on API 30 and 36.1 for empty-bootstrap
offline activation and a runnable app with a live non-sticky worker service.
Cancelling confirmation preserves existing PIDs; confirming replaces main,
terminates the worker and preserves recovery. No adb force-stop substitutes for
the restart action. Timeout, respawn, shared-UID and PID-race cases remain open.

Earlier API 30 evidence (dedicated `emulator-5594`): the production public bootstrap/control/
offline suite passed, followed by `python3 integration-v1/controls-shortcut.py
--serial emulator-5594`, proving runnable payload -> launcher shortcut -> private
controls. The shortcut test used the build before C's second checkpoint. After
importing that checkpoint and adding Restart app, the combined normal/public/
controls/offline suite passed again on API 30. It still uses adb force-stop for
cold activation and did not validate the new restart action. That specific gap
is superseded by the 2026-09-23 runs above, not by a full V1 acceptance claim.

Reproduce the new cases after building the normal/empty/public fixture and VPK:

```sh
python3 delivery/device-tests/public_bootstrap.py --serial <emulator> --restart-controls
python3 integration-v1/controls-shortcut.py --serial <emulator> --restart-worker
```

The second test traverses the actual launcher shortcut UI; it refreshes drawer
coordinates between identically named normal/shell icons. Recorded AVDs are
Restart30 (`emulator-5586`) and Restart36 (`emulator-5584`), isolated under
`/tmp/paravoid-restart-avds`. No phone was used.

## Quarantine confirmation

`quarantine-controls.py` passed on both Restart30 and Restart36 on 2026-09-23.
It installs the production signed embedded fixture whose Application deliberately
throws. Cancelling retry leaves the checksummed selection journal unchanged;
confirming retries the exact active identity with no healthy fallback or pending
replacement. Confirmed shell restart then actually executes the broken payload,
which re-quarantines. The recovery PID survives, and ordinary re-entry proves the
recovery process has no generation mappings or lease descriptors. No adb force-stop
stands in for the cold-restart action; journal decoding is read-only test evidence.

Build and run (only dedicated disposable emulators; the fixture app is replaced):

```sh
./gradlew -p compatibility/complete-v1 assembleParavoidAndroidDebug \
  -Pbootstrap=embedded -Pgeneration=broken -PpayloadVersion=2 -Pauthentication=public
python3 integration-v1/quarantine-controls.py --serial <emulator> --avd <owned-avd> \
  --apk compatibility/complete-v1/build/outputs/paravoid/paravoidAndroidDebug/shell.apk
```

Do not run this build while a delivery test is serving files from the same build
directory. These tests cover intact startup-failed generations, not corruption,
stale-dialog identity races or retry with a pending forward repair.

Remaining: storage coordination/reservation, cold-restart edge cases,
cross-process cancellation/auth device coverage, quarantine race/corruption cases
and current-APK refresh coordination. C's broader startup-failure and
independent security-review gates remain open. Do not mark V1 complete here.
