# Finish integration

Worktree `/tmp/paravoid-v1-integration-finish`, branch `v1/integration-finish`.
Starts from Track A `da45412`, imports B `6208140`, `4b72682`, `f06cc5d`,
`00a321f`, `bb8fb17`, `3a4610d`. B's `00a321f` is C's `3281e6e`; skip that
duplicate when importing subsequent C work. C's uncommitted work is untouched.

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

Remaining: storage coordination, shell-owned runnable-app controls access,
quarantine device validation, current-APK refresh coordination and integration of
Track C's pending work. Do not mark all finish gaps complete from this checkpoint.
