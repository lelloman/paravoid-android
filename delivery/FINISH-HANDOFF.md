# Track B finish handoff, 2026-09-22

Branch **v1/delivery-finish**, worktree **/tmp/paravoid-v1-delivery-finish**,
started from main **74256422648a790f8dac8716a844d65d4003f3d5**.
No pushes, publishing, phone use, other-worktree edits or generated key commits.
This is a tested delivery slice, **not a claim that Track B or V1 is complete**.

## Ordered commits

1. `6208140` — shared controller attempt exclusion, restart throttling, refresh
   preferences before foreground decisions; host controller tests.
2. `4b72682` — refresh cannot unlink a staging-borrowed source; invalid grants
   discard idle partials; old credential partitions cannot poison replacement
   suppression; host borrowed-source/replacement tests.
3. `f06cc5d` — atomic interprocess preference updates, durable credential binding,
   Retry-After/deadline/budget persistence, cancellation cleanup, retry consumption
   before HTTP so death during the final retry cannot replay it indefinitely.
4. `00a321f` — imported Track C commit **3281e6ed627715f0300cc5594651be5021537829**.
   Includes its API 30 fixture readAllBytes fix and embedded forward repair/startup
   validation. **Skip this duplicate when integrating with Track C.** B made no
   direct edits to the runtime or shared fixture/interface sources.
5. `bb8fb17` — disabling automatic checks prevents delayed automatic retries.
6. Subsequent test/docs commit — storage rejection regression, reproducible B-owned
   installed-device scripts and this handoff; see branch log for its commit ID.

## Implemented and host-tested

- A file lock beside the shared preference file excludes simultaneous controller
  attempts across main/recovery, including scheduled retry waits. Process death
  releases it. Lifecycle selection locks are never held for transport.
- Existing APK startup preserves six-hour timestamps. A changed persisted
  credential partition resets the timestamp. Preference updates serialize
  read/modify/write, preserving changes from the other process.
- Retry deadlines/budgets persist in no-backup storage; foreground entry can resume
  an eligible saved retry. This does not add a background service or alarm.
  Explicit actions begin a new attempt. Cancellation removes its saved retry.
- Automatic retries re-read current preferences; disabled checks stop them and
  download/unmetered preferences are evaluated at each request attempt.
- Installed invalid credentials deny updates, never anonymous fallback. Cleanup
  defers while a transfer/staging borrow holds its lock and removes the source on
  return. Per-directory scope checks reject obsolete clients and old auth failures.
- Insufficient storage fails before archive HTTP/staging, without undoing the
  authenticated lifecycle head observation or changing runnable lifecycle state.
  This last assertion uses the host lifecycle seam, not a device ENOSPC experiment.

Validation commands:

```sh
bash delivery/test.sh
bash delivery/check-android.sh
git diff --check
```

Final run: **290 Java assertions**, **6 Java/Python HTTP interop assertions**,
**8 Python HTTP tests**, **4 Python personalization tests**, Android 36 source
compilation and `git diff --check` all passed. Python HTTP tests use real
loopback sockets; APK personalization tests use aapt2/apksigner and temporary keys.
The only javac warning is the existing shared ContractException serialVersionUID.
Build tools: Java 21 compiling Java 11 target, Python 3.12, Android build-tools 36.0.0,
Gradle 8.13, fixture compileSdk 36. Normal packaging behavior remains unchanged.

## Installed-device evidence

Exact commands, image revisions and test limits:
[delivery/device-tests/README.md](device-tests/README.md).
Production components used: automatic Gradle shell/VPK assembly, installed APK
policy/grant readback, signed metadata verifier, actual HTTP reference server,
archive verifier, runtime admission/staging, and shell-owned controls.
No HTTP or lifecycle verifier mock is present in the installed paths.

Final **API 30 and 36.1 public bootstrap/control/offline tests passed**, including
normal packaging controls on both. Both API 30 and 36.1 APK-key
suites passed: missing/invalid/expired grants, explicit retry, authenticated 403,
suppression across restart, APK replacement during an outstanding archive request,
new-credential staging, revocation and offline use of the accepted payload.
The first API 30 public run passed download/staging and controls but exposed the
fixture's unavailable InputStream.readAllBytes call on payload launch. The owner
fixed it in 3281e6e, imported here as 00a321f; final reruns use that fix and pass.

## Remaining coordination and gates

Exact proposals and rationale are in [COORDINATION.md](COORDINATION.md).

- Runtime still gives clients **per-process delivery directories**. The controller
  attempt lock and preferences are shared, but HTTP suppression/partials and direct
  cancellation need shared client storage or explicit runtime coordination. A 403
  in recovery followed by an immediate main launch caused no extra request in the
  device test, but six-hour throttling also applies: this is **not** proof of shared
  auth suppression after the interval expires. A control in another process cannot
  yet disconnect the currently active process's request.
- Runtime currently passes `shellOnly` as `emptyBootstrap` on every recovery resume.
  It should use the actual EMPTY state and preserve normal recovery throttling.
  Live APK credential refresh callbacks still need C's integration agreement.
- Runnable-app shell controls need a reachable route independent of payload UI.
  Tests reach the production screen through empty bootstrap. Quarantine confirmation
  exists, but its device gate and exact retryable snapshot identity remain open.
- Storage keeps the existing conservative `2 * archiveSize + 2 GiB + 64 MiB` check
  pending agreement with C. It bounds B/C archive copies plus the V1 maximum
  materialization and avoids deleting protected runtime generations/history, but
  can reject tiny updates unnecessarily and **is not a durable space reservation**.
  Proposed smaller bound is `3 * archiveSize + 64 MiB`: outer STORED inventory
  cannot exceed signed archive size, and C does not recursively extract nested
  APK/JAR data. C's preparation checks remain authoritative; optional-history
  eviction and shared reservations require its ownership. No device ENOSPC claim.
- No full device proof of quarantine retry, network-metering transitions, request
  cancellation from the other process, or every process-death/reboot scheduling
  boundary. Retry restart/budget/cancel and metered decisions are host-tested.
- HTTPS production personalization remains host-tested; device carriers deliberately
  use the debug-HTTP fixture insertion path. V4, signing-block ecosystem review,
  independent security review, real-app updates and physical ARM64 remain gates.
