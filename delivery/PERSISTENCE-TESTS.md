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
  halfway through or inject disk errors. The selected deletion boundaries below
  do not exhaust arbitrary simultaneous cancel/write interleavings.
- A cancellation interrupted before publication may leave its previous epoch.
  The test does not claim an uncommitted cancellation was acknowledged or durable.
- Retry/cancellation writers now reuse one temporary slot per record under a
  permanent cross-process writer lock and same-VM serialization. A process death
  may leave that slot, but repeated deaths cannot add slots; the next successful
  write removes it. Readers never treat it as authority. Lock files are not
  removed. This does not sweep random temporary files left by older builds or
  cover other record types (preferences/credential markers).

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

## Bounded writer slots

The host matrix repeats every death before recovery, asserts at most one slot,
then asserts no temporary records remain after a successful retry/cancel write.
An additional contention case runs two local writer threads (25 writes each)
alongside eight child-process writers, separately for retry and cancellation.
Every writer must succeed; final records must parse and no slot may remain.
This exercises the same locks/atomic publication used on Android, not a simulated
filesystem. The full host delivery suite passed (`/tmp/paravoid-bounded-records.log`).
Installed reruns of all ten original write cases passed on API 30/36.1 after the
writer change: `/tmp/paravoid-bounded-records{30,36}.log`. Two additional installed
deletion cases per API passed with `public_bootstrap.py --persistence-delete`:
`/tmp/paravoid-deletion{30,36}.log`. They terminate the real controller before
unlink and after unlink/before directory force, preserve active PID/archive and
both journals, reject surviving cancelled retries and complete an explicit check
after recovery. The full `--persistence-crash` command now includes deletion too;
the reported runs executed the ten-write and two-deletion subsets separately.
Installed deletion also asserts at most two retry/cancellation slots and no
remaining slots after the final successful operation. No physical power-loss claim.

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

Those original ten device cases cover retry first publication (explicit cancellation ends the
prior attempt before Check now) and cancellation replacement. They do not reproduce
all host first-write/replacement combinations by themselves; the extension below
covers the complementary combinations. Neither proves physical power loss or
arbitrary concurrent write schedules. Early harness runs failed on attempt
ownership and adb missing-file result handling; those were corrected before both
complete passing runs. Both disposable emulators were stopped afterward.

## Complete installed write-combination matrix

`bash delivery/device-tests/persistence-matrix.sh SERIAL [HOST_SERVER_PORT]` runs
20 write cases (retry/cancel × first publication/replacement × five boundaries)
plus the two deletion cases. Build the ordinary empty/public A/p1 fixture first
and use only disposable emulators. Different host ports permit independent APIs
to run concurrently; never run two fixtures on the same emulator simultaneously.

The added retry-replacement mode arms the debugger for the second production
write, then returns an actual two-second Retry-After. The first failure writes a
schedule; the real scheduled attempt durably consumes the next retry before HTTP.
Death must preserve retry count 1 before publication or count 2 after publication.
No record bytes, device clocks or scheduler fields are injected. After death the
server returns a long retry interval so recovery can be inspected deterministically.

Each first-cancellation boundary starts from a fresh shell-data installation and
activates a real signed VPK. The harness asserts that no cancellation record
exists, then interrupts the first actual Cancel download action. This avoids
deleting an old epoch or substituting handcrafted record bytes to manufacture the
precondition. All cases check retained main/archive/security state and successful
explicit checking after recovery.

The complementary cases passed on both API 30/36.1 x86_64 on 2026-09-23:
`/tmp/paravoid-replacement{30,36}.log` and
`/tmp/paravoid-firstcancel{30,36}-{created,written,synced,renamed,directory-synced}.log`.
Together with the ten-write and two-deletion runs above, this completes **22
installed boundaries per API** against production `d7f7072` (the original ten
write/deletion runs preceded the unrelated quarantine/packaging fixes). Commands
were executed as subsets, not one invocation of the new convenience driver.
No production code change was needed for the complementary cases.
