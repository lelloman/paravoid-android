# Runtime finish coordination proposals

Worktree `/tmp/paravoid-v1-runtime-finish`, branch `v1/runtime-finish`, baseline
`7425642`. Shared baseline commits are already present; no duplicate imports.

The following changes have **not** been made in B's delivery tree or A's plugin/
contract tree. They require agreement with the owning track.

## B: cold-start recovery and ownership

The current `CompleteRuntime` creates a controller in every process, partitions
transport files by process, and calls `refreshInstalledApk()` on every startup.
The baseline controller treats this as credential replacement and resets the
foreground interval, even when the installed APK is unchanged. Preferences share
one file but controllers cache their own values. Proposed contract:

- One recovery-process controller owns transport, preferences and retry scheduling.
  Payload processes send shell-owned foreground notifications only; no HTTP work
  or grant material in those notifications. B supplies a controller bootstrap that
  distinguishes initial same-APK read from actual installed identity replacement.
- C supplies fresh launcher routing and a cold-boundary readiness check. B displays
  a shell-owned "Open app" action after bootstrap/repair. The action creates only
  a new ordinary launcher Intent; it does not replay original extras/deep links.
- A cached unavailable main process cannot re-enter its one-time
  `Application.attachBaseContext` by launching another Activity. A failed process
  can also retain a payload lease after caught startup failure. Merely saying
  "close and relaunch" is insufficient to guarantee a genuine cold start.
  We need an agreed explicit restart interaction. Never automatically terminate
  processes that entered payload code or release their leases early.

Required tests: repeated normal foreground starts preserve six-hour throttling;
worker starts do not create independent transfers; recovery bootstrap -> downloaded
pending -> explicit fresh launch progresses with no manual adb force-stop; live
payload service delays activation; cancellation and replacement invalidate old
scheduled work; recovery never maps payload files or acquires generation leases.

## B/shared contract: preflight and optional cleanup

C currently owns `RuntimeLifecycle.cleanup()`, and staging calls it. B's preflight
runs before staging and requires a conservative maximum extraction allowance.
No shared reservation API exists. Proposed shared change for review: a bounded
preflight operation on Lifecycle, receiving signed archive size, serializing
optional-history eviction with preparation, then returning an explicit admission
or insufficient-space result. B holds its existing transfer lock for its own
partial. Staging remains synchronous and non-cancellable once handed off.

A free-space check alone is not a durable reservation. Define whether reservation
means an allocated no-backup file or serialized competing download admission before
adding the method. Protected/active/pending/leased generations and security history
must never be removed. Test two processes contending, cancellation, process death,
insufficient disk, and concurrent cleanup with active leases on both Android APIs.

## A: component startup failures

The generated declared-service dispatcher is used by unavailable adapters; no
replacement classifier has been introduced. Device tests now exercise the actual
JobService declaration instead of substituting a plain Service.

Construction exceptions from custom component factories currently escape without
reporting startup failure to C. Factory construction can be caught in C's own
entry points. Exceptions thrown inside framework-dispatched provider/Activity/
service lifecycle callbacks need a deliberate integration mechanism: generated
wrapping or a narrowly scoped startup exception observer. An arbitrary lifetime
uncaught-exception handler must not label later application crashes as startup
failures. Agree the supported callback boundary and test both startup failure and
later crashes before claiming that gate complete.
