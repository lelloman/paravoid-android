> Current finish-track changes, device results and unresolved gates: [FINISH-HANDOFF.md](FINISH-HANDOFF.md).

# Track B: delivery internals

Branch `v1/delivery`, baseline `8c40885`. Shared foundation `7ace172` is imported as
`050982e`; signed metadata commit `d58457f` is imported as `73df1bb`.
`DeliveryClient` uses its actual `MetadataVerifier` and `Lifecycle`
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

Storage integration now uses `Lifecycle.reserveDownload`: one cross-process claim
spans HTTP transfer and staging, with lifecycle-owned cleanup and a budget of three
archive copies + 64 MiB. This replaces the fixed 2 GiB materialization allowance.
It serializes cooperating writers, not physical disk allocation; staging still
rechecks actual capacity. Cancellation/failure closes the claim, and process death
releases its OS lock. See V1 §9 and `lifecycle-tests/SpaceAdmissionTest.java`.

Historical track handoff (consult the local remaining-gaps handoff for current gates):
- C's snapshots, recovery routing, retention and explicit confirmed retry actions.
- A's complete VPK verifier and real executable A/B archives. Real
  `SignedMetadataVerifier` and checked-in metadata vectors now test the client;
  state-machine fault tests separately use `delivery/test/.../FakeMetadata`, never
  shipped or called by production wiring. No permissive VPK verifier exists here.

`DeliveryClient.check` is a blocking worker operation. Its verified head cache is
memory-only, scoped to the session/request, bounded by wall and elapsed time, and
never reused as admission authority. It borrows no generation lease and cannot
execute/activate payloads. A stage result is only pending/already pending/selected.
Grant lifetime is checked before requests and rechecked by C at admission/staging.
The installed credential initializer fails closed and removes B-owned partials.
Authentication failure keeps the existing payload entirely outside B's control.

Implemented additional slices: bounded installed base-APK grant extraction,
signature-preserving v2/v3 personalization using A's grant verifier, asynchronous
controller, persisted non-security preferences/auth suppression, transfer exclusion,
and a shell-owned Android update/bootstrap/recovery screen. See `android/README.md`
and `tools/README.md` for exact integration and limitations.

Remaining integration: A's pinned shell-policy carrier and build wiring, C's
runtime/recovery routing, APK-replacement notifications,
Android backup exclusion proof and device tests. Personalization has no v4 support
and requires externally supplied APK-pinned policy until its carrier is defined.
No production-ready claim. Tests never install on a device or publish anything.

No signed JSON parser, security high-water database, generation selection or loader
is implemented here. Transport success never means admission, staging or activation.
No APK/device/publishing operations are part of the host tests.

## Validation

Run `delivery/test.sh` from this worktree. Uses only JDK 11+ and Python 3, with build
output in a temporary directory; no Gradle outputs from another worktree.
Personalization tests additionally use Android build-tools 36.0.0 and platform 36
to create and sign ephemeral APKs. `bash delivery/check-android.sh` compiles shell
UI against Android 36; source compilation is not device acceptance.

Current verified coverage: 107 transport, 36 retry-policy, 55 delivery/lifecycle,
8 APK-carrier, 33 asynchronous-controller and 14 real signed-metadata assertions;
8 Python server tests (including 6 Java/Python interop assertions), plus 3 real
APK-personalization tests. The only javac warning is the unchanged shared
`ContractException` missing `serialVersionUID`; no shared code is modified by B.
