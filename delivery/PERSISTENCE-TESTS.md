# Retry/cancellation persistence crash tests

Run `bash delivery/test.sh` with a full JDK including `jdk.jdi`.
`PersistenceDeathTest` launches the real production record writer in a child JVM,
sets source-line debugger breakpoints and forcibly terminates that process while
suspended. It does not throw a synthetic exception or allow writer `finally`
cleanup to run. A separate fresh JVM reads each surviving record.

The matrix contains 20 cases: retry/cancellation × first write/replacement ×
five boundaries. Source formatting separates existing statements for debugger
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
