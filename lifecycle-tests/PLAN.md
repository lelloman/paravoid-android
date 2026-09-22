# Track C lifecycle work

Worktree: `/tmp/paravoid-v1-lifecycle`, branch `v1/lifecycle`, baseline `8c40885`.
Read PARALLEL-IMPLEMENTATION.md, V1.md and PACKAGING.md, and inspected existing
ShellApplication, factory, embedded materializers and resource/class loaders.

## Slices

1. Private bounded checksummed atomic records, directory durability and OS leases;
   standalone host subprocess tests, independent of shared Gradle/plugin outputs.
2. Owned immutable publication, interrupted-publication retry and protected cleanup.
3. After Track A foundation: durable authenticated time/revision/version admission,
   selection/trials and shared snapshots/actions. No duplicate signed-object parser.
4. Integrate one leased, reverified complete generation before user constructors;
   coordinate loader/factory edits and component inventory with A/B.
5. Unavailable adapters and health hooks; dedicated API 30/36.1 emulator tests,
   migration/forward repair and failure injection. Never target a physical phone.

## Coordination required

Track A's shared compilable foundation commit is not present at baseline. Please
supply its hash and fixtures before production integration. Internals here are
package-private, have no public admission/verification/lifecycle contract, and are
not yet wired into startup. Track B owns UI and transfer files; successful staging
will only publish pending state.

Need foundation decisions for verified metadata exact body/scope identities,
credential replacement epochs, boot/elapsed clock evidence, component-kind
inventory (JobService and foreground-service adapters), recovery-process routing,
and full generation materialization verifier outputs. These are interface questions,
not proposed alternative security semantics.

Lock order: JVM registry monitor -> selection OS exclusive lock -> nonblocking
exclusive generation lease (activation/cleanup), or shared generation lease
(startup). Verification and copying must happen outside selection. Lease files
are permanent, separate from deletable generation directories. One channel per
lock inode per process avoids POSIX close-of-another-descriptor lock release.
All runtime users must share this registry/classloader. No PID/time heuristic.

Durable records are private local storage framing, not signed metadata parsers.
Missing established records must fail closed; initialization is an explicit caller
operation, never a read fallback. Root initialization and APK-pinned floors remain
foundation integration work. Checksums detect damage, not malicious local tampering.

Host process tests do not prove Android lock/filesystem behavior or power-loss
semantics. Android adapters, admission, health, unavailable behavior and acceptance
remain unimplemented until subsequent slices. Runtime state must live under Android
noBackupFilesDir. Physical ARM64/release acceptance remains an external final gate.

## Implemented private slices and evidence

`AtomicRecord`: bounded versioned SHA-256 framing; missing/corrupt records throw;
file force, atomic replacement and parent-directory force. Caller serializes writes.
No logical selection or anti-replay schema exists yet. Do not treat opaque record
storage as implemented durable admission.

`ProcessLocks`: process-wide registry, exclusive selection transactions, shared
process-lifetime leases, nonblocking exclusive cleanup checks. Nested selection
and lease access outside selection are rejected. Channels/lease files remain
permanent; never close a second descriptor for the same lock inode. Requires one
runtime classloader and exclusive use of these lock files. Registry resource bounds
for a long-lived recovery process still need integration review.

`OwnedArchive`: bounded private copy, size/digest checks, injected verification,
read-only atomic publication to unique names, full recheck before use and protected
cleanup. The injected test verifier is a private fake, not release authentication.
Publication does not update selection. Production materialization, signed identity
retry/deduplication, orphan enumeration, reservation accounting and retention policy
are pending. An interrupted publication may leave an unreferenced owned archive;
that must never become selected merely because it exists. No production caller is
wired to these primitives.

Run `bash lifecycle-tests/run.sh` (JDK 21.0.12, compiled with `--release 11`).
Passing on Linux host:
- Child-process halt before rename, after rename and after directory sync.
- Every-byte corruption and every-prefix truncation of a framed record; missing
  record, oversized write, missing directory and injected pre-rename I/O failure.
- Two independent shared lease holders, forced death of one then both; repeated
  same-process lease acquisition; exclusive operations remain blocked appropriately.
- Four processes racing 160 serialized read/modify/fsync/rename transactions.
- Mutable download source isolation; wrong digest, overlong/truncated input,
  fake verification rejection, writable/corrupt owned bytes, protected/leased cleanup.

These tests use subprocess death, not machine power loss. ENOSPC/device EIO,
Android directory-force support, API 30/36.1 process races and all actual startup,
health and component behavior remain untested. No emulator or physical device has
been used in this preparatory slice. No main-checkout files or shared build wiring
were changed. Next step requires the shared foundation commit and component-kind
inventory from Track A; production integration is intentionally not invented here.

## Foundation integration and admission (follow-up)

Track A commit `7ace172` is now integrated as `486e69d`. Runtime's dependency on
`:paravoid-contract` is added in this branch; A should retain that line on integration.
No shared types or signatures were changed. Tests construct evidence only through
`lifecycle-tests/TestEvidence.java`, never production constructors or a bypass.

`AdmissionStore` consumes shared verified heads/releases and persists app-lineage
identity mappings, contract/channel revision floors, request-body equality, credential
epochs, admissions and authenticated/boot-relative time. Credential replacement
invalidates admissions without clearing replay history. Non-available heads advance
revision/time state. Invalid observations commit nothing. Repeated identical heads
return the existing admission. Missing/corrupt established state fails closed.
A final publication callback rechecks admission while holding the common selection
lock, preventing credential/supersession races between checking and publication.
No copy or signature verification is permitted in that callback.

The admission helper remains package-private until the complete Lifecycle facade
is assembled. Explicit new-store initialization rejects any existing directory,
including an interrupted empty one; automatic repair/reset is intentionally absent.
History capacity exhaustion fails rather than discarding identity mappings. State
schema migration and user-visible repair routing remain integration requirements.
The clock adapter must supply stable boot identity and boot elapsed time on Android.

Host admission tests cover persisted reopen, equal revision/different device scopes,
identity reuse, rejected input not poisoning floors, non-available heads, invalidation,
wall/elapsed rollback, reboot, contract replacement and corrupt/missing state.
APK-key tests cover replacement races, denied public fallback and grant expiration.
These are fake-authenticated-object tests, not executable signature-vector evidence.
