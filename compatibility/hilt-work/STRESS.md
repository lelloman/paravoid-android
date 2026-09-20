# WorkManager chain, retry and active-cancellation probe

This extends the [Hilt Work fixture](README.md), using its pinned WorkManager
2.10.1, Hilt 2.57.2/AndroidX Hilt 1.2.0 and both optional Paravoid integrations.
Normal and shell APKs run the same public WorkManager APIs. There is no target-side
instrumentation, WorkManager test driver, fake clock, internal database editing or
manual initialization workaround.

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/stress-check.sh
python3 -m unittest discover -s compatibility/hilt-work -p test_stress_assertions.py
```

Use a dedicated unlocked emulator with Python 3.8+ and adb. The script builds lazy
mode, installs the two fixture APKs, and clears only their data per scenario.
The exported `StressControl` receiver is a **fixture-only control/observation
surface**, not a production API or a downstream integration requirement. It
enqueues/cancels unique work and queries public WorkInfo; preferences are output
only. Never copy that unauthenticated exported control surface into a real app.

## Scenarios

1. Enqueue a delayed Hilt worker and a blocked ordinary Worker dependent. Verify
   OS job registration, then kill the enqueueing process. The first worker starts
   cold with no Activity, a fresh injected graph and the real Application context.
2. Return `Result.retry()`. Query WorkInfo until ENQUEUED/attempt 1 is committed
   and the dependent is still BLOCKED. Kill that process too. Honor the persisted
   30-second backoff deadline before asking JobScheduler to dispatch if needed.
3. Attempt 1 runs in another process/graph and returns Data. HiltWorkerFactory's
   default reflective fallback creates the **non-Hilt** dependent. It validates
   the inherited run token and Unicode output. Both work records must reach
   SUCCEEDED, with matching IDs/output, and survive another process death.
4. Separately cold-start a Hilt worker which waits for cancellation. Verify
   RUNNING/BLOCKED states, cancel the unique chain, observe `onStopped` with
   `isStopped() == true`, and verify both records are CANCELLED. Kill the process
   and query again: the recently enqueued parent must retain its cancelled state;
   the dependent may be retained as CANCELLED or pruned. It must not execute.

Each control query has a fresh request ID; each scenario has a fresh run token.
PID and graph comparisons distinguish real process death from Activity recreation.
The driver verifies committed WorkInfo, not just worker-side success messages.
Forced OS dispatch happens only after WorkManager's persisted deadline. This is
not a delivery-time or background-quota guarantee.

## Findings in the normal control

- After clearing app data, receiver-only startup left the API 36.1 package in
  standby bucket NEVER with WITHIN_QUOTA unsatisfied. Even forced job dispatch did
  not deliver it. Setup now performs an ordinary Activity launch, then backgrounds
  and kills that enqueueing process. Worker processes still must have no Activity.
  No global quota, standby or device-idle policy is changed.
- WorkManager 2.10.1 prunes a cancelled **never-enqueued** dependent when its
  database reopens. Its WorkSpec `last_enqueue_time` starts at -1; database cleanup
  uses that field plus minimum retention and the pruning threshold for completed
  work. This occurred in normal **and** shell packaging. Requiring every cancelled
  WorkInfo to remain queryable was an incorrect test assumption, not a Paravoid
  failure. Cancellation is asserted before shutdown, the parent's retained record
  is checked afterward, and a bounded negative observation checks that the child
  did not run. Five pure-Python tests ensure pruning cannot hide missing/changed
  parent state, unexpected work or a dependent that was never cancelled.

## Verification evidence

On an API 36.1 x86_64 emulator, debug normal and shell packaging each pass the
retry chain and active-cancellation scenarios (four passing cases). Both observe
the cancelled dependent being pruned on database reopen. Both variant lint tasks
pass, as do the five cancellation-retention assertion tests and the five shared
process-kill guard tests. The existing lazy Hilt/Room cold-worker regression also
passes in both modes, with persistence across three PIDs. No production runtime
or plugin change was required.
These new scenarios have not yet been verified on API 28/29 or other ABIs.

## Coverage limits

This is a two-node sequential chain, one linear-backoff retry and active
cancellation of its root. It does not prove exactly-once side effects, infinite
non-execution, arbitrary DAG/fan-in behavior, input-merger variants, duplicate
unique-work policies, failure cascades, constraints changing mid-work, CoroutineWorker,
periodic/foreground work, reboot, payload-version migration or library-version
compatibility. Process death can interrupt a worker before its result commits;
this retry scenario deliberately kills **after** the retry state is committed.
WorkManager's database is not a permanent application audit log.
