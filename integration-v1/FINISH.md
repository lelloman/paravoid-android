# Finish integration

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
This action compiles in the combined fixture but is not yet device-validated.

API 30 evidence (dedicated `emulator-5594`): the production public bootstrap/control/
offline suite passed, followed by `python3 integration-v1/controls-shortcut.py
--serial emulator-5594`, proving runnable payload -> launcher shortcut -> private
controls. The shortcut test used the build before C's second checkpoint. After
importing that checkpoint and adding Restart app, the combined normal/public/
controls/offline suite passed again on API 30. It still uses adb force-stop for
cold activation and does not validate the new restart action. API 36.1 combined
acceptance and a shortcut rerun on the final build remain pending.

Remaining: storage coordination/reservation, validation of explicit cold-start recovery,
cross-process cancellation/auth device coverage, quarantine controls device
validation and current-APK refresh coordination. C's broader startup-failure and
independent security-review gates remain open. Do not mark V1 complete here.
