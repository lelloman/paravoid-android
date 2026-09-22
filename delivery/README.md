# Track B: delivery internals

Branch `v1/delivery`, baseline `8c40885`. This directory is deliberately not wired
into the Android build until Track A supplies the shared foundation. Package-private
transport seams and test values are not alternative verification/lifecycle APIs.

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

Requested the shared-foundation commit through the session on 2026-09-22. Needed:

- Actual module/package/signatures for verified scope, head, grant, expected archive,
  observeHead and staging; stable error mapping and authenticated clock access.
- C's credential-scope invalidation operation (including in-flight staging), and
  copy-completion/ownership rules for B-owned completed files.
- Storage reservation API: C's bounded materialization requirement and optional
  history eviction must precede B's download; B must not delete C's storage.
- C's snapshots, recovery routing, retention and explicit confirmed retry actions.
- A's grant verification API and shared positive/negative grant/head vectors.

No signed JSON parser, security high-water database, generation selection or loader
is implemented here. Transport success never means admission, staging or activation.
No APK/device/publishing operations are part of the host tests.

## Validation

Run `delivery/test.sh` from this worktree. Uses only JDK 11+ and Python 3, with build
output in a temporary directory; no Gradle outputs from another worktree.
