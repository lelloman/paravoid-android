# Track B: delivery internals

Branch `v1/delivery`, baseline `8c40885`. Shared foundation `7ace172` is imported as
`050982e`. `DeliveryClient` uses its actual `MetadataVerifier` and `Lifecycle`
interfaces. Android build wiring remains with A. Package-private transport seams
and test values are not alternative verification/lifecycle APIs.

## Plan

1. Bounded HTTP transport with explicit origin, redirect, encoding and MIME rules;
   opaque head bytes and hash-checked downloads tested with private connection fakes.
2. Resumption, cancellation, credential-scoped partials, authentication suppression
   and nonblocking bounded retry/foreground scheduling policy.
3. Store-independent Python serving of pre-signed head fixtures and immutable VPKs;
   authentication before conditional/range handling and host transport tests.
4. Integrate A's head/grant verification and C's admission/staging interfaces, then
   installed-APK grant carrier/personalization and shell-owned controls.

## Foundation coordination / questions

Requested the shared-foundation commit through the session on 2026-09-22; discovered
and imported the committed interface slice from `v1/packaging`. Resolved:

- Uses `com.lelloman.paravoidandroid.contract`, `observeHead` for every verified
  outcome including 304, `setCredentialScope` for replacement, and borrowed-file
  synchronous staging followed by B cleanup. C remains the freshness authority.

Still needed for production completion:
- Storage reservation API: C's bounded materialization requirement and optional
  history eviction must precede B's download; B must not delete C's storage. Current
  client conservatively requires two archive copies + 2 GiB materialization + 64 MiB
  headroom. It never evicts C's history; concurrent storage reservations are pending.
- C's snapshots, recovery routing, retention and explicit confirmed retry actions.
- A's real verifier and shared positive/negative grant/head vectors. Tests use
  `delivery/test/.../FakeMetadata`, never shipped or called by production wiring.

`DeliveryClient.check` is a blocking worker operation. Its verified head cache is
memory-only, scoped to the session/request, bounded by wall and elapsed time, and
never reused as admission authority. It borrows no generation lease and cannot
execute/activate payloads. A stage result is only pending/already pending/selected.
Grant lifetime is checked before requests and rechecked by C at admission/staging.
The installed credential initializer fails closed and removes B-owned partials.
Authentication failure keeps the existing payload entirely outside B's control.

Remaining integration: installed base-APK extraction/personalization, asynchronous
controller and persisted preferences, shell UI/routing, Android backup exclusions,
and device tests. Package-private scheduling policy is tested separately; callers
must not block startup or run `check` on the UI thread. No production-ready claim.

No signed JSON parser, security high-water database, generation selection or loader
is implemented here. Transport success never means admission, staging or activation.
No APK/device/publishing operations are part of the host tests.

## Validation

Run `delivery/test.sh` from this worktree. Uses only JDK 11+ and Python 3, with build
output in a temporary directory; no Gradle outputs from another worktree.
