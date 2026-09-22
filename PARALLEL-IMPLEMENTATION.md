# Parallel v1 implementation agreement

Agreed 2026-09-22. Starting implementation baseline: `aeefcb3`.

This document divides the remaining work into three tracks. [V1.md](V1.md) is
the selected product, archive, signing and update design; [DISTRIBUTION.md](DISTRIBUTION.md)
and [PACKAGING.md](PACKAGING.md) provide supporting context. Do not invent a
second protocol or relabel the current embedded profile as complete v1.

**Status:** this commit records ownership and interface semantics. The Java
interfaces below are illustrative signatures, not existing APIs. A shared,
compilable foundation commit and executable vectors must follow before consumers
wire production integration. The internal interface agreement is not a public
wire freeze or completed security review.

## 1. Tracks and ownership

| Track | Owns | Does not own |
| --- | --- | --- |
| A — Packaging and verification (primary agent) | Gradle integration, complete VPK assembly/signing, shell contract generation, shared models/parsers/verifiers and executable format vectors | HTTP scheduling, active-generation selection |
| B — Delivery and controls (first delegated agent) | HTTP discovery, grant authentication, resumable downloads, scheduling/cancellation, shell-owned update/bootstrap/recovery UI, reference server and APK personalization tools | Payload execution, generation selection, durable anti-replay authority |
| C — Runtime lifecycle (second delegated agent) | Admission and durable security state, immutable generation storage, selection journal, process leases, early loading, startup health, retention, unavailable-component adapters | HTTP transport, VPK production |

Recovery is **forward-only**, not automatic rollback. A payload that entered user
code may have migrated application data. Previous archives are retained for
diagnostics or deliberate repair, not arbitrary execution of older code.

Track B owns the UI; Track C owns the lifecycle state/actions that UI presents.
No login/OIDC flow, store-specific API, hot swap, automatic app-data deletion or
production verification bypass is in scope.

## 2. Shared foundation (Track A coordinates)

Before production integration, commit a small Android-independent Java contract
module containing immutable value types, interfaces and stable error codes. Its
exact module/package names and complete method signatures are fixed in that
foundation commit; consumers must not create competing public definitions.

Include:

- Shell policy, request scope, verified head/release, expected archive identity,
  admission identity, staging result and update snapshot types.
- Verification, admission/staging and lifecycle/control interfaces.
- Exact selected VPK/release/head/grant schemas and runtime ABI constants.
- Shared positive/negative vectors and two compatible release fixtures.
- Storage ownership, file-handoff rules, lock ordering and crash boundaries.

Use V1's existing limits, signature roles, canonical writer rules, exact-byte
signature verification and explicit rejection semantics. Changes require a
documented amendment plus shared vectors; no silent per-branch schema changes.
Do not freeze the external protocol before independent Python/Android agreement,
security review and the APK signing-block ID collision review required by V1.

## 3. Verification boundary: A supplies B and C

Illustrative API:

```java
VerifiedHead verifyHead(byte[] envelope, ShellPolicy policy, RequestScope scope);
VerifiedRelease verifyVpk(File archive, ShellPolicy policy, ExpectedArchive expected);
```

`ShellPolicy` identifies the installed app/contract, trusted keys, capability
requirements and installed distribution policy. `RequestScope` binds the exact
request, including channel and device/process capabilities. `ExpectedArchive`
binds the head's release identity, archive size/hash and release-envelope hash.

Verification authenticates and checks structural/content/compatibility rules.
It neither selects nor executes a generation, nor grants durable replay admission.
The same shared signing/envelope primitives serve release, head and grant roles;
Track B owns APK grant extraction/provisioning and credential lifecycle.

Embedded payloads use a separate explicit verification/admission entry point;
there is no boolean that disables authentication. They still require a release
signature and compliance with installed policy and persisted version floors.

`VerifiedRelease` is descriptive evidence, not a permanent guarantee that its
source file remains unchanged. File ownership and verification at use matter.

## 4. Admission/staging boundary: B calls C

Illustrative API:

```java
AdmissionResult observeHead(VerifiedHead head);
StageResult stageDownloaded(File completedArchive, AdmissionId admission);
UpdateSnapshot snapshot();
```

Track C is the sole durable authority for authenticated time/revision floors,
app-lineage payload-version history and admission records. Track B must not
maintain an independent competing anti-replay database. B submits authenticated
heads, including authenticated non-available statuses, to C for the applicable
freshness/history checks. Invalid input must not advance trusted state.

Admission binds the exact head, request/credential scope and offered archive.
C rechecks authorization/freshness when staging and rejects stale or superseded
admission. Credential replacement invalidates old-scope HTTP cache/partials and
admissions without resetting anti-replay history or disabling accepted offline
payloads. Revocation does not promise to cancel an already authorized transfer.

B's verified HTTP cache is only a cache, never admission authority. In particular,
304 requires a still-fresh verified body for the identical request/credential
scope and must not extend its lifetime.

C copies completed download bytes into private C-owned staging and invokes A's
verifier on those bytes before publishing immutable generation storage. B never
hands a mutable download path directly to the loader. File-handoff completion and
cleanup behavior must be explicit in the foundation API.

Successful staging means **pending**, never running. B cannot force activation,
kill payload processes or bypass generation leases. Crash boundaries must never
quietly reset security history; interrupted admission/publication must allow
identity-preserving retry while rejecting different bytes under a known identity.

## 5. Lifecycle/control boundary: C supplies loader and B's UI

C supplies:

- A process-lifetime leased generation handle containing one coherent set of
  DEX/resource/Java-resource/native paths and signed release identity.
- Startup-progress, healthy and caught-failure reporting hooks matching V1.
- Snapshots distinguishing active/pending identities, trial/recovery state,
  update activity, storage and actionable errors. Current app usability is not
  inferred merely from the latest download error.
- Explicit retry/retention/control operations. Retrying an intact quarantined
  generation requires explicit user confirmation; there is no generic rollback.
- Shell-owned unavailable-component adapters and the bootstrap/recovery routing
  hooks consumed by B's screens, before any payload is runnable.

No component independently selects its generation. Install resource/native/class
loading paths before user constructors/providers, with no network operation
blocking early startup. A shell-only recovery process loads no user code and
holds no payload generation lease.

## 6. Storage, concurrency and safety

- B owns HTTP cache and resumable partial/download files.
- C owns accepted archives, materialized generations, security state, selection
  journal and leases. All such state is private and excluded from Android backup.
- Credentials never enter shared snapshots, journals, diagnostics or logs.
- C defines one lock order and proves OS-backed lease semantics. No HTTP, UI
  wait or long archive transfer/verification while holding the selection lock.
  Publish selection with a short atomic critical section after preparation.
- Reverify before each process loads a generation. Cleanup cannot remove leased,
  selected or protected pending/trial generations.
- Failed download/verification leaves a runnable selected generation usable.
- No automatic reset of corrupt anti-replay state, anonymous authentication
  fallback, component-level version mixing or execution of partial downloads.

## 7. Branch and integration workflow

Suggested branches: `v1/packaging`, `v1/delivery`, `v1/lifecycle`, each in its own
worktree. Do not change another agent's checkout or rebase another agent's branch.
Share the foundation commit across all three before production integration.

Until that commit is available, B/C may prepare scoped test plans, transport or
lifecycle internals with private fakes. Do not claim those fakes are the agreed
production interface or block useful independent work waiting for implementation.

A coordinates shared build wiring, root documentation and changes to shared
interfaces. B/C keep track-specific implementation/tests/docs separate and report
required shared changes. C owns edits to existing runtime loading/factory entry
points; A coordinates any changes it needs there rather than independently
rewriting the same files.

Commit small verified slices and report hashes, tests and unresolved assumptions.
Integrate incrementally: shared foundation; embedded full VPK; public download to
pending; coordinated cold activation; authenticated delivery; unavailable-payload
recovery/controls; fault injection and real-app acceptance.

Do not run builds against another worktree's plugin output. Isolate Gradle/TestKit
project output paths, or serialize affected builds until isolation is proven.
Assign dedicated emulators per device-testing track; never use the user's phone
without explicit authorization. Physical ARM64/release acceptance remains a final
gate, not a consequence of emulator success.

## 8. First delegated agent: Track B brief

Read this document, V1.md and DISTRIBUTION.md completely before implementing.
Create a dedicated worktree/branch from the agreed baseline. Your scope is Track B
in section 1. Coordinate with the primary agent for the shared foundation commit.

Start with a small implementation/test plan and a transport slice: bounded HTTP,
strict origin/redirect/content-encoding rules and public head/download handling.
Then add signed-head integration/admission handoff, resumable download rules,
credential provisioning/authentication and replacement behavior, scheduling and
shell-owned controls. Use shared verification rather than duplicating security
parsers. Keep the Python reference server independent for cross-checking vectors.

Test public and apkKey modes; 401/403 suppression with explicit retry; no anonymous
fallback; fresh scoped 304 versus expired/wrong-scope cache; correct and malformed
206, 200 replacement and bounded 416 retry; cancellation/interruption/resumption;
size/hash/truncation errors; redirect rejection; credential replacement; retry
budgets; offline operation with an accepted payload; and no execution/activation
triggered by successful download. Track C supplies admission/lifecycle fakes or
the shared interfaces until its implementation is available.

Document requirements/limitations and keep frequent commits. Do not publish,
install on a phone, modify store services, relax the selected security profile or
implement selection/anti-replay authority in the delivery layer. Report interface
ambiguities for explicit resolution rather than inventing incompatible behavior.
