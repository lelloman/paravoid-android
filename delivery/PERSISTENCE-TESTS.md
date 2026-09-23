# Retry/cancellation persistence crash tests

Run `bash delivery/test.sh` with a full JDK including `jdk.jdi`.
`PersistenceDeathTest` launches the real production record writer in a child JVM,
sets source-line debugger breakpoints and forcibly terminates that process while
suspended. It does not throw a synthetic exception or allow writer `finally`
cleanup to run. A separate fresh JVM reads each surviving record.

The write matrix contains 20 cases: retry/cancellation × first write/replacement ×
five boundaries, plus two retry-deletion cases. Source formatting separates existing statements for debugger
precision; no production fault hook or persistence behavior was added.

| Stop before | State reached | Expected authoritative record after process death |
| --- | --- | --- |
| Content write | Temporary file created/opened | Previous record, or absent |
| File sync | Content written | Previous record, or absent |
| Atomic rename | File content synced and closed | Previous record, or absent |
| Directory force | Rename completed | Complete new record |
| Temporary-file cleanup | Parent directory force and close completed | Complete new record |

For cancellation, an old-epoch retry is intentionally retained. After a published
cancellation, the exact crash-surviving files are also fed to a new production
controller with host fake transport/lifecycle collaborators: automatic resumption
must send no HTTP, clear the stale retry and report cancellation. A new explicit
check must still work. Every case then performs a successful normal cancellation
and retry write/read, proving abandoned temporary files are not treated as authority.
Existing controller regressions cover cancellation before/after a stale retry write.

Passed 2026-09-23: all 20 cases, the complete delivery Java/Python suite, and Android
36 source compilation. Logs: `/tmp/paravoid-persistence-death.log` and
`/tmp/paravoid-persistence-android.log`. No production logic fix was needed.

## Limits

- This is host filesystem/process-death evidence, not installed ART/emulator or
  physical ARM64 evidence. Installed tests previously covered death during HTTP
  retries after counters were persisted; these are distinct gates.
- Renamed-but-not-directory-synced data remains visible after a process kill on
  the live host filesystem. This does **not** prove survival of power/cache loss.
- Breakpoints bracket Java write calls; they do not interrupt a kernel write
  halfway through or inject disk errors. Retry deletion interruption and arbitrary
  simultaneous cancel/write interleavings are not exhausted by this matrix.
- A cancellation interrupted before publication may leave its previous epoch.
  The test does not claim an uncommitted cancellation was acknowledged or durable.
- Interrupted temporary files can remain. Readers ignore them; this test does
  not implement or prove bounded long-term reclamation of those files.

## Retry deletion

The host harness also kills a real production controller before unlinking a stale
retry and after unlinking it but before forcing the parent directory. Cancellation
has already been durably published. A fresh reader sees respectively the old,
invalidated retry or no retry; in the first case a fresh controller sends no HTTP,
removes the stale record and still accepts a new explicit check. The deletion is
the production `clearRetry` path, not a test-side substitute.

Passed 2026-09-23 along with all 20 write cases and the full delivery Java/Python
suite: `/tmp/paravoid-retry-deletion.log`. These two cases are host process-death
tests, not installed deletion or physical power-loss evidence.

## Installed production-controller matrix

Build the complete fixture as empty/public A/version 1, then run:

```sh
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5586 --persistence-crash
python3 delivery/device-tests/public_bootstrap.py --serial emulator-5584 --server-port 18766 --persistence-crash
```

Passed 2026-09-23 on Restart30/API 30 and Restart36/API 36.1, x86_64, runtime
`509cb65`: ten cases per API, retry/cancel × all five boundaries above. The
installed APK is built from the actual production code; only the external harness
is new. `PersistenceDeathGate` terminates recovery through JDWP at each production
statement, without running application cleanup. No root, injected record bytes or
simulated controller is used. The test first activates a genuinely signed VPK.

A real HTTP 503/Retry-After creates retry scheduling. Cancellation publishes its
actual epoch. At every death boundary the main process, active archive hash and
selection/security records survive. A fresh recovery controller, opened through
the real launcher shortcut, resumes valid schedules or rejects a cancelled retry.
An explicit update succeeds after the matrix. Logs:
`/tmp/paravoid-persistence-device{30,36}.log`; build log:
`/tmp/paravoid-persistence-device-build.log`.

These device cases cover retry first publication (explicit cancellation ends the
prior attempt before Check now) and cancellation replacement. They do not reproduce
all host first-write/replacement combinations, retry deletion, physical power loss
or arbitrary concurrent write schedules. Early harness runs failed on attempt
ownership and adb missing-file result handling; those were corrected before both
complete passing runs. Both disposable emulators were stopped afterward.
