# Track B coordination proposals, 2026-09-22

Baseline 7425642; B branch v1/delivery-finish. No runtime/shared edits made.

## Track C: requested agreement

1. Use one no-backup `paravoid-delivery` directory across main and recovery instead
   of process partitions. B already uses an OS transfer lock; credential partitions,
   suppression and partial invalidation must span both processes. Controller attempt
   locking uses the already-shared preference path and includes retry delays.
   Test: overlapping foreground/recovery requests, 403 in one process suppresses
   the other across restart, replaced grant invalidates old caches/partials.
2. Call `foreground(true)` only when the lifecycle snapshot is EMPTY, not for every
   shell-only Activity resume. Normal and recovery foreground use the six-hour rule.
   Call installed refresh with current PackageManager sourceDir on APK changes.
   Same-credential startup must preserve last-check state (B fixes controller reset).
3. Storage estimate can be `3 * signedArchiveSize + 64 MiB` before download.
   One B archive, one C private archive, materialized outer inventory <= archive
   size because complete VPK outer ZIP entries are STORED. Nested APK/JAR content
   is checked but not recursively materialized. C already checks `2 * sourceSize`
   before copying and actual inventory size before extraction. B cannot evict C
   generations. Agree on optional-history cleanup and reservation ownership before
   adding a shared API; these checks alone are not OS disk reservations.
   Test: small archive on <2 GiB free storage succeeds; genuinely insufficient
   storage preserves selected payload and replay history; simultaneous staging
   cannot promise the same space twice.
4. The runnable app needs an accessible shell-owned updates route. Private
   ShellUpdatesActivity exists but no UI route is available in the complete fixture.
   On API 36.1 both ordinary and complete launches pass; a shell `am start` is denied
   by the private Activity boundary (correct). Empty mode remains testable.
5. Snapshot needs a precise retryable quarantined identity, or documented assurance
   that RECOVERY + active is sufficient and retryQuarantined rejects other causes.
   UI confirms the captured identity and never infers rollback safety.

Track C commit 3281e6e was imported as 00a321f for fixture/startup validation.
No agreement on the proposals above has been received. These are proposals, not claims
that storage reservation, process integration or controls reachability are complete.

## Resolved API 30 fixture blocker

Production empty bootstrap, authenticated staging and shell controls pass on API
30. Offline payload launch fails at complete-v1 MainActivity.java:14 with
`NoSuchMethodError: InputStream.readAllBytes()`. That fixture uses a Java API not
available on API 30. Replace its two readAllBytes calls with an 8 KiB streaming
ByteArrayOutputStream helper, then rebuild normal and complete fixtures and rerun
B device scripts. No reusable fixture source was changed without owner agreement.

Track C independently fixed this in 3281e6e; imported as 00a321f. Reruns use
the owner-authored fix; there are no B-authored shared fixture/runtime edits.
