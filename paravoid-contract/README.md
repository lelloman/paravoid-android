# Shared v1 contract (Track A)

Pure Java 11 module, no Android/HTTP/storage dependencies. Android consumers use
`implementation project(':paravoid-contract')`. Included-build consumers will need
explicit composite wiring; no binary publishing is implicit.

Import `com.lelloman.paravoidandroid.contract.Protocol.*` for immutable values.
`MetadataVerifier` and `VpkVerifier` are implemented by A; `Lifecycle` and
`GenerationLease` by C. B calls metadata verification and lifecycle admission,
then downloads into its own private directory. Successful downloads are handed
to `Lifecycle.stageDownloaded`; B never calls a classloader or selects a version.

## Precise handoff semantics

- `verifyHead` authenticates schema/signature/request scope, **not current time or
  replay acceptance**. C owns clock injection, authenticated time floors, boot
  elapsed-time state and replay history. B uses `observeHead` even for cached 304
  heads and non-available outcomes. The verified object contains exact signed body
  bytes for equal-revision comparison and exact envelope hash for HTTP validators.
- `verifyGrant` authenticates schema/signature/audience but not wall-clock validity.
  B enforces grant lifetime before HTTP; C rechecks at admission and staging.
  `CredentialScope.provisioned` carries only the exact grant envelope SHA-256 and
  signed times, never a bearer key. Public uses the reserved scope ID `public`.
- Call `setCredentialScope` on installed-credential refresh. Null denies updates;
  it is not a public credential. `observeHead` receives the request's captured
  scope so C can reject replacement races. C verifies mode and exact current scope,
  persists the scope binding as needed, and never resets high-water state on change.
- Admissions are opaque C-issued durable references, not bearer credentials or
  self-authorizing tokens. Staging resolves/rechecks their signed identity and
  freshness. No admission from an HTTP cache alone.
- `stageDownloaded` synchronously borrows the supplied complete file until return.
  Caller must not mutate it during the call and remains responsible for deletion
  on success or failure. C copies to its private staging and verifies that copy;
  a copy race can only cause rejection, never trusting earlier verified bytes.
  C must reverify the owned generation before each process load.
- Staging may perform bounded disk work; call off the UI thread. B can cancel its
  download before handoff. Once staging begins, it completes/rejects atomically;
  cancelling UI interest does not authorize interrupting a selection transaction.
- `verifyEmbedded` and `verifyRetained` do not require a fresh head, but still
  authenticate the complete archive and enforce installed compatibility. C checks
  persisted floors/accepted identity, including across APK replacement.
- `GenerationLease` intentionally has no public close operation: production code
  cannot release it while classes/threads remain alive. C retains the underlying
  OS-backed lock for process lifetime. Tests simulate lifecycle in separate
  processes or use a private test harness, not a production early-release API.
- C durably marks trial entry before returning usable payload execution paths or
  before `beforeUserCode` returns; consumers call it before any user code. The
  implementation must not rely on a callback made after class initialization.
- `LifecycleSnapshot` is C's state only. B combines it with its own transport
  progress/preferences to render the overall update screen. There is no shared
  mutable UI snapshot or cross-track JSON database.

All failures use stable `ContractException.Code` values with sanitized messages.
Never attach untrusted parser/network causes that might contain credentials.
Verified model constructors are package-private so callers cannot accidentally
replace verification with DTO construction. This is an API guardrail, not a
security sandbox against app code already executing in the same process.

## Status

Implemented: compilable interfaces/value types, immutable-value tests, strict
bounded JSON and `SignedMetadataVerifier` for head/grant/trust metadata. Checked-in
positive/negative signed metadata vectors are under `src/test/resources/metadata-vectors`.
They include two head versions, not complete A/B VPK archives.

`CompleteVpkVerifier` now authenticates complete signed archives, checks installed
scope/SDK/ABI/floors, exact inventory coverage/hashes, resource-ID reservations,
DEX headers/checksums and ELF ABI headers. Its bounded ZIP reader checks local and
central records, CRCs, ranges, compression, nested APK/JAR content and expanded
limits without extracting. `VpkWriter` produces deterministic STORED archives,
verifies a private candidate, then atomically replaces the output. Failed builds
do not publish an unverified candidate. It is a build-side API, not a runtime
signing facility. Production signing private keys are never archived.

These checks do not constitute bytecode/ELF semantic verification or recompilation
of resource tables; Android still validates components at load time. The publisher
build gate is responsible for producing the declared installed resource boundary.
Admission, current-credential authority and coordinated execution remain separate.
See `integration-v1/README.md` for real Android-content A/B fixtures and integrated
host delivery/lifecycle proof. Gradle complete-mode wiring and installed-app launch
acceptance are separate remaining gates, not implied by a valid archive.
The wire schemas remain V1.md; public interoperability/security freeze is pending.
