# Paravoid distribution specification

Protocol v1, draft 0.1 — 2026-09-21.

**Design only; not an implemented or frozen wire protocol.** This document defines
the store-independent contract and a proposed HTTP binding. MUST/MUST NOT denote
requirements of this draft, not current capabilities. The unresolved items in
section 9 block a v1 interoperability or secure-delivery claim.

Current production packaging embeds a DEX-only `module.zip`. It does not produce
signed VPKs, support empty shells, download updates or atomically activate them.
Complete resource/native packaging and generated shell compatibility checks also
remain incomplete. See [PACKAGING.md](PACKAGING.md) and [ROADMAP.md](ROADMAP.md).

## 1. Scope and ownership

Paravoid specifies the payload artifact, discovery/download protocol, trust and
authentication interfaces, and shell bootstrap/activation behavior. The Gradle
plugin produces artifacts; the shell implements the distribution client. Any
server implementing the protocol can distribute them. Neither an installed store
app nor a particular identity provider is required.

Catalogs, app types, upload APIs, publisher accounts, access-group administration,
payments, deployment and store management UI are outside this specification.
Server authorization policy is independent of the protocol's authentication hook.
Shell APK distribution remains an ordinary Android installation/update concern.
Paravoid can report that a new shell is needed; it does not silently install one.

V1 distributes one complete VPK, including all packaged resource configurations
and ABIs. Configuration-targeted downloads, deltas and live code replacement are
out of scope. Do not rename today's `module.zip` to `.vpk` and claim conformance.

## 2. Identities and compatibility

| Identity | Meaning |
| --- | --- |
| `applicationId` | Installed Android package, including any Paravoid suffix; not the Java namespace |
| `shellContractId` | Digest of installed requirements defined in PACKAGING.md; initially an exact match |
| `releaseId` | Immutable payload release identity within the application; never reused for different contents |
| `payloadVersion` | Monotonically increasing payload release number within the application lineage; independent of APK versionCode |
| `channel` | Configured publication stream; labels such as stable/beta have no built-in access rights |
| `headRevision` | Monotonic publication-metadata revision for an application/contract/channel stream, including withdrawals and refreshes |

A VPK MUST identify its application, required shell contract, payload format and
runtime requirements, SDK/ABI requirements and resource-ledger lineage. The shell
MUST validate these locally even when the server selected the candidate. A package
name, version number or matching signer alone is not compatibility evidence.
Changing the shell's installed components/pinned resources still requires an APK
update. Payload compatibility does not establish database rollback safety.

The shell's trusted configuration includes the application/contract identities,
distribution base URL, channel policy, supported protocol/format versions, trust
roots, credential-provider selection and allowed network origins. Endpoints and
public client identifiers may be build configuration; publisher keys, shared
client secrets and user tokens MUST NOT be embedded through the Gradle DSL.

## 3. VPK artifact and trust

`.vpk` names the new outer complete-payload container described in PACKAGING.md:
code, compiled resources/assets, Java resources, native libraries, and metadata
form one coherent release. Archive layout and serialization are not frozen here.

The signed release manifest MUST bind all identities/requirements above and an
inventory of every loadable component: role, path, byte length and digest. Sign
the manifest using an unambiguous encoding and domain separation; the signed
inventory authenticates component bytes. No executable/resource component may
sit outside that inventory. The final VPK's byte length and digest are bound by
signed discovery metadata, avoiding a self-referential archive hash.

The verifier MUST reject altered/missing/unexpected components, duplicate or
unsafe paths, links escaping extraction roots, unsupported critical fields or
formats, conflicting identities, and invalid/untrusted signatures. Enforce bounds
on metadata, entry count, individual/aggregate expanded sizes and nested archives
before loading or extracting content. Numerical bounds are a format-profile
release blocker, not an unlimited default. Existing DEX-reader limits still apply
to code components until a separately tested change supersedes them.

Signing authority originates in the product shell's trusted release policy. A
server response MUST NOT establish a new trust root by presenting its own key.
Private keys stay in release infrastructure, not the shell or payload. Publisher
signing and online head-publication signing may use separately delegated keys,
but scopes, rotation, expiry and revocation must be defined before release.
This draft does not require exporting or reusing an APK private key online.

Public downloads MUST receive the same signature/compatibility checks as private
downloads. HTTPS and user authentication do not replace release verification.
Payload code executes with the app's privileges; this is not a sandbox for
untrusted extensions.

## 4. Discovery: proposed HTTP v1 binding

Paths below are relative to a configured distribution base URL, not a store API:

```text
GET v1/apps/{applicationId}/head?contract={shellContractId}&channel={channel}
GET v1/apps/{applicationId}/releases/{releaseId}/payload.vpk
```

The head request additionally supplies device SDK, ordered supported ABIs and
supported runtime/payload-format versions. Their wire encoding is a section 9
blocker. They describe capabilities, not an authenticated device identity.

The server selects a permitted, compatible release for that stream, not simply
the largest APK versionCode or newest VPK for any shell. A head response describes
one of these signed outcomes:

- `available`: release identity/version, format/requirements, manifest digest,
  VPK byte length/digest and download locator.
- `no-compatible-release`: no offered payload fits this request. This is not a
  command to erase or disable a previously verified local payload.
- `shell-update-required`: an explicit publication-policy outcome, not something
  the client guesses from any newer incompatible upload.

Every outcome binds the application/contract/channel, selection requirements,
protocol version, head revision, issue/expiry times and authorized signer. Error
responses need not be signed, but cannot authorize code, change trust or erase
local state. Shell-update hints are informational, never APK-install authority.

Use GET with ETag/If-None-Match for efficient polling. A literal HTTP HEAD is
optional and does not replace the signed descriptor. A 304 allows reuse only of
a matching cached descriptor whose signature, scope and freshness remain valid;
it cannot extend signed expiry. Expired metadata requires a fresh valid descriptor
before selecting a new release. Conditional HTTP semantics follow
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html).

HTTP outcomes: 200 carries a descriptor, 304 has no body, 401 requests credentials,
403 denies access, and 429/5xx are retryable with bounded backoff. A server may use
404 to conceal unauthorized/unknown resources; clients cannot treat it as proof
that no compatible release exists. Authentication failures MUST NOT trigger a
silent retry as an anonymous user unless public mode was explicitly configured.

Protected metadata, conditional responses and downloads all require authorization;
knowing a release ID or ETag is not access. Caches MUST NOT share private results
across credentials or selection scopes. The concrete cache/header profile is
frozen with the schema, including capability-dependent ETags.

## 5. Download and publication behavior

Release bytes are immutable. Publish a new head only after its complete referenced
artifact is durably downloadable; clients must nevertheless handle disappearance
or interrupted transfers without losing their current payload.

The baseline transport is HTTPS. Servers SHOULD support byte ranges; clients
must also work with a complete 200 response. Resume only against the same signed
artifact identity and strong validator, checking Content-Range. A full response
to a range request replaces, rather than appends to, the partial file. Verify the
complete artifact afterward; a successful transfer or ETag is not authenticity.
Range and validator behavior follows [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html).

Enforce signed sizes and local download/storage limits. Stage into private files,
never activate partial bytes, and defend verification/loading against file
replacement races. Retrying or refreshing credentials must preserve identity
checks; resumability cannot mix bytes from releases or authorization sessions.

Only configured HTTPS origins may receive requests. Credential providers scope
authorization to an origin/audience; credentials MUST NOT automatically follow
cross-origin redirects. CDN delegation needs explicit configuration. V1's baseline
does not put reusable bearer credentials in URLs or logs. Exact redirect and
optional short-lived download-ticket rules need conformance tests.

Metadata revision/freshness checks must prevent accepting previously superseded
heads as new updates. Clients persist verified high-water marks, scoped to their
publication stream, and do not reset them on logout or channel switching. Ordinary
updates cannot silently downgrade payload versions. Authorized recovery is a
separate action with persistent-data checks, not an exception inferred from a
valid old signature. Fresh installation/data loss and hostile device clocks need
an explicit security model: this draft does not claim local counters alone solve
replay or freeze attacks. Review against [TUF's update threat model](https://theupdateframework.github.io/specification/latest/)
before choosing the final signing/freshness profile; TUF is not yet a dependency.

## 6. Optional distribution authentication

Public mode needs no user credentials. Protected mode uses a shell-owned,
replaceable credential provider available before any payload exists. Paravoid
specifies the interface and outcomes, not a mandatory store login or OIDC issuer.

The interface must support obtaining scoped request authorization, expiry/refresh,
interactive-sign-in-required, cancellation, denial and logout. Public configuration
and runtime secrets stay separate. Only foreground shell UI may initiate an
interactive flow; background entry must return/defer safely rather than open login
unexpectedly. Bound refresh/retry loops and redact credentials from diagnostics.

Provider implementation, UI resources, callbacks and dependencies must be part of
the installed shell contract, not dynamically loaded app code. No authentication
cycle may require downloading the VPK in order to authenticate its download.
Store-app brokers can be optional adapters, not a baseline requirement.

An optional OAuth/OIDC profile should use external-browser authorization with
PKCE and no embedded shared client secret, following
[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html). Concrete provider APIs,
client registration and token audience/scope are not frozen by this draft.

Distribution login and the payload application's login are separate sessions.
They may use the same human identity/SSO provider, but tokens MUST NOT be assumed
interchangeable or forwarded to the other service automatically. Download access
does not grant app-backend access, and vice versa.

V1's proposed offline policy is to retain/run an already verified compatible
payload when the server is unavailable or a download credential expires/is
revoked. Revocation stops authorized future downloads; it is not remote deletion
or DRM for code already delivered. Different execution-licensing requirements
would need a separate explicit specification. Never erase user data on auth failure.

## 7. Embedded and empty bootstrap; activation

The target plugin offers two explicit options (DSL names still to be defined):

| Mode | Without network on first launch | After a verified payload is retained |
| --- | --- | --- |
| Embedded | Start the bundled compatible release | Start the selected compatible local release |
| Empty | Show shell-owned bootstrap/retry UI; the app cannot run yet | Start the selected compatible local release |

An embedded payload uses the same signed VPK format as a standalone release. The
default remains embedded for the packaging milestone; empty is opt-in only after
the payload-absent lifecycle gate passes. An empty APK still contains the manifest,
pinned resources, runtime, trust/auth configuration and bootstrap UI/dependencies.
It does not mean an APK with no resources or no component declarations. Today,
removing DEX alone would still leave application resources/native libraries installed.

The first download may be any currently offered compatible release, not necessarily
the release built with the shell. There is no protocol requirement to retain the
first VPK forever. A distributor supporting fresh installs of a shell contract
must keep a suitable release available or explicitly end support. An embedded
asset remains part of its APK until APK replacement; runtime cleanup cannot shrink it.

Empty-shell startup MUST be safe for providers, receivers, services, direct
Activities/deep links and additional processes, not just the launcher. Do not
instantiate missing payload classes, block startup on a network fetch, fake
successful work, or replay arbitrary privileged Intents after login. Component
unavailability/defer/error semantics and safe intent preservation require a proven
design before this mode ships; disabling every component is not an assumed solution.

Activation proceeds through downloaded -> verified -> staged -> selected at a
coordinated cold start -> healthy. Keep the previous usable version while staging;
commit selection atomically and recover from interrupted writes. No in-process
class/resource hot swap. All app processes must obey a common activation generation;
do not activate a new version while older processes can still operate on shared data.
Attach resources/native paths before payload Application/providers as PACKAGING.md
requires, then preserve normal Android initialization ordering.

Failed verification/activation must leave a usable compatible version or a clear
shell recovery UI. Retention must not remove active, staged or recovery-required
versions. A startup failure alone does not authorize code rollback: database/schema
compatibility and startup health policy are separate, still-unimplemented gates.

## 8. Conformance gates

Before shipping, exercise at least:

- Independent client/server implementations against shared positive/negative wire
  vectors; unknown versions/critical fields and malformed inputs fail explicitly.
- Public and protected delivery with identical integrity checks; unauthorized
  metadata/range/download/304 access, expired tokens, cancelled login and redirects.
- Modified manifests/components, wrong application/contract/key, expired/replayed
  heads, unsupported device, malicious archives and bounded resource exhaustion.
- Interrupted/resumed download, changed range validator, lost connectivity, low
  storage, publisher deletion races and crash-safe staging/selection.
- Empty first launch, offline retry, every supported cold component entry, and
  successful download followed by a process restart without any store app installed.
- Embedded offline start, newer compatible release bootstrap, multiple processes,
  startup failure, safe retention, and rejected unsafe database rollback.

Use API 30 and 36.1 as the initial complete-packaging device gates, with physical
ARM64 coverage before claiming release readiness. Existing DEX-only fixture results
do not count as passing these distribution gates.

## 9. Decisions required before freezing v1

1. Exact archive layout, manifest/head schemas, encodings, integer/time rules,
   extension rules, media types, numeric limits and HTTP capability parameters.
2. Reviewed signature algorithms/envelopes, canonical bytes, delegated authority,
   trust-root rotation/revocation, signed freshness, clock/first-install behavior
   and recovery authorization. Supply cross-implementation security test vectors.
3. Credential-provider API/lifecycle and at least one independently usable protected
   transport profile, including credential storage, refresh and redirect tests.
4. Payload-absent Android component behavior, cross-process activation mechanism,
   startup health and persistent-data recovery policy, proven in focused fixtures.
5. Plugin DSL, packaging/trust reports and build-baseline workflow, aligned with
   the completed automatic packaging implementation.

Review these as small specification/implementation slices. Do not implement a
store-specific upload model to fill protocol gaps or present this draft as a
finished secure updater. Backend integrations can follow once the relevant wire
and security profiles are frozen and executable conformance tests exist.
