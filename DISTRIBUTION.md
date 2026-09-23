# Paravoid distribution specification

Protocol v1, draft 0.4 — 2026-09-21.

Draft 0.2 replaces the generic credential-provider/OIDC proposal with two modes:
public access or a distributor-provisioned key inside the shell APK.
Draft 0.3 makes key replacement exclusively distributor-managed through a new
shell APK installation/update; Paravoid does not rotate or renew update keys.
Draft 0.4 selects the implementation profile in [V1.md](V1.md): concrete formats,
trust/authentication, freshness, activation, empty-shell behavior and build API.

**Design only; not an implemented or frozen wire protocol.** This document defines
the store-independent contract and a proposed HTTP binding. MUST/MUST NOT denote
requirements of this draft, not current capabilities. V1.md resolves the design
choices below; section 9 lists the remaining freeze/verification gates, which
still block a v1 interoperability or secure-delivery claim.

Current production packaging embeds a DEX-only `module.zip`. It does not produce
signed VPKs, support empty shells, download updates or atomically activate them.
Complete resource/native packaging and generated shell compatibility checks also
remain incomplete. See [PACKAGING.md](PACKAGING.md) and [ROADMAP.md](ROADMAP.md).

The isolated [APK provisioning experiment](compatibility/provisioning/README.md)
provides a Python test server and normal/shell emulator fixtures for public/key
access, signature-preserving APK personalization, revocation and APK-delivered
replacement keys. A [signed-profile extension](compatibility/provisioning/SIGNED.md)
additionally checks issuer-signed grants, publisher-signed discovery, scoped
persisted replay protection and verified harmless transfers. Neither suite serves
VPKs or executes downloads. Their provisional records and HTTP routes are not this
draft's frozen protocol or a production updater; complete format/trust policy,
production integration and activation remain outstanding.

The [component-inventory extension](compatibility/provisioning/ARCHIVE.md) now
verifies a separately signed manifest, complete inventory coverage and component
bytes within a bounded stored-ZIP fixture. It tests hostile archives beneath valid
signed discovery, not a frozen VPK format, real component loading, SDK/ABI/runtime
compatibility or executable activation.

The optional [cold-DEX experiment](compatibility/provisioning/COLD-DEX.md) now
downloads separately compiled, signed A/B code and executes it through the existing
Paravoid DEX loader on process startup. It covers staged-only downloads, offline
execution, re-verification of retained bytes and failure without automatic rollback.
It is a single-process test entry point, not replacement of the whole payload
application, production activation, or a tested crash-safe/multiprocess protocol.

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
roots, authentication mode and allowed network origins. Public settings belong in
build configuration. In key mode the distributor provisions an app-scoped update
credential into the delivered shell APK; the developer MUST NOT bake a shared key
for every user into the Gradle DSL. Publisher signing private keys and general
store-account credentials MUST NOT be included in the APK or payload.

## 3. VPK artifact and trust

`.vpk` names the new outer complete-payload container described in PACKAGING.md:
code, compiled resources/assets, Java resources, native libraries, and metadata
form one coherent release. V1.md selects the archive layout and serialization;
executable conformance and review are still required before wire freeze.

The signed release manifest MUST bind all identities/requirements above and an
inventory of every loadable component: path, byte length and digest, with role
determined unambiguously by the format's reserved paths. Sign
the manifest using an unambiguous encoding and domain separation; the signed
inventory authenticates component bytes. No executable/resource component may
sit outside that inventory. The final VPK's byte length and digest are bound by
signed discovery metadata, avoiding a self-referential archive hash.

The verifier MUST reject altered/missing/unexpected components, duplicate or
unsafe paths, links escaping extraction roots, unsupported critical fields or
formats, conflicting identities, and invalid/untrusted signatures. Enforce bounds
on metadata, entry count, individual/aggregate expanded sizes and nested archives
before loading or extracting content. Numerical bounds are selected in V1.md;
their enforcement is a release blocker. Existing DEX-reader limits still apply
to code components until a separately tested change supersedes them.

Signing authority originates in the product shell's trusted release policy. A
server response MUST NOT establish a new trust root by presenting its own key.
Publisher signing private keys stay in release infrastructure, not the shell or payload. Publisher
signing and online head-publication signing use separate APK-pinned key sets in
v1, with no online delegation. Trust changes require a shell APK update; V1.md
defines scopes, credential expiry and the limitations of this policy.
This draft does not require exporting or reusing an APK private key online.

Public downloads MUST receive the same signature/compatibility checks as private
downloads. HTTPS and download authentication do not replace release verification.
Payload code executes with the app's privileges; this is not a sandbox for
untrusted extensions.

## 4. Discovery: proposed HTTP v1 binding

Paths below are relative to a configured distribution base URL, not a store API:

```text
GET v1/apps/{applicationId}/head?contract={shellContractId}&channel={channel}
GET v1/apps/{applicationId}/releases/{releaseId}/payload.vpk
```

The head request additionally supplies device SDK, ordered supported ABIs and
supported runtime/payload-format versions. V1.md defines the additional query
parameters and signed request scope. They describe capabilities, not an
authenticated device identity.

The server selects a permitted, compatible release for that stream, not simply
the largest APK versionCode or newest VPK for any shell. A head response describes
one of these signed outcomes:

- `available`: release identity/version, manifest digest and VPK byte length/digest;
  request capabilities are signed and the download route is derived from releaseId.
  The signed release manifest supplies the full requirements checked before loading.
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
replacement races. Retrying after an APK-provisioned key replacement must preserve identity
checks; resumability cannot mix bytes from releases or authorization sessions.

Only configured HTTPS origins may receive requests. Update credentials are scoped
to an origin/audience; credentials MUST NOT automatically follow
cross-origin redirects. The selected v1 profile rejects all redirects and has no
CDN delegation/download tickets. It never puts bearer credentials in URLs or logs.

Metadata revision/freshness checks must prevent accepting previously superseded
heads as new updates. Clients persist verified high-water marks, scoped to their
publication stream, and do not reset them on logout or channel switching. Ordinary
updates cannot silently downgrade payload versions. Authorized recovery is a
separate action with persistent-data checks, not an exception inferred from a
valid old signature. V1.md selects forward-only recovery, APK floors and explicit
fresh-install/data-loss/hostile-clock limitations. Its threat review draws on
[TUF's update threat model](https://theupdateframework.github.io/specification/latest/)
without claiming TUF compliance or making it a runtime dependency.

## 6. Public or distributor-provisioned key authentication

Paravoid defines exactly two configurable distribution-authentication modes:

| Mode | Shell behavior | Distributor responsibility |
| --- | --- | --- |
| Public | Check/download without credentials | Serve publicly accessible signed releases |
| Key | Read the provisioned update key from the shell APK and authorize requests without user interaction | Authorize acquisition, provision/revoke the key, deliver replacements through shell APK updates |

Authorization established when acquiring the shell carries forward to subsequent
updates until the distributor expires or revokes that entitlement. The distributor
may associate the key with a user, purchase or another entitlement internally;
Paravoid does not need that account model or the user's store login tokens.
The payload application's own authentication is unrelated and remains unchanged.

### Provisioning boundary

In key mode the delivered shell APK MUST contain the initial update credential in
a versioned, bounded provisioning record that the shell can read before loading
any VPK. The record identifies its application, credential ID/issuer and key
material. V1.md defines the signed grant fields and APK carrier.
There is no required first-launch login, store-app service handshake or separate
configuration-file import. An empty shell must be able to authenticate using only
its installed contents and the configured distribution service.

The key authorizes only the intended application's update operations and permitted
channels, not publishing, APK signing, VPK signing or general store-account access.
Issue distinct credentials per authorized acquisition/grant rather than one global
application secret. APK copying means this alone does not prove a unique device
or installation; do not label the key a hardware-bound identity.

The distributor personalizes the APK; Paravoid defines the provisioning contract,
not which store performs it. V1 selects a custom APK signing-block entry preserving
developer signatures; store-managed APK signing is not mandatory. The production
implementation still needs signature-preservation, installed
readback, v4/checksum and update-path tests before adoption.

The shell MUST NOT infer authenticity of an unprotected provisioning entry from
the APK's developer signature. The provisioning profile must define issuer
authentication and bind the credential to the expected application/service.
Personalization MUST NOT replace trusted VPK signing roots, redirect credentials
to arbitrary endpoints or change the installed shell contract. Per-grant credential
bytes are excluded from the shell contract ID; authentication mode, trusted issuer
policy and the provisioning format remain part of that contract.

### Requests, store-managed key replacement and failures

The shell uses the APK-provisioned key for app-scoped discovery/download
authorization over HTTPS using the Bearer profile in V1.md.
V1 has no key-renewal endpoint, refresh-token exchange or Paravoid-managed
key rotation; a VPK or head response cannot install a replacement credential.
No distribution browser login, OAuth redirect Activity or installed store app is
required by either Paravoid mode. The distributor may itself use OIDC or any other
authentication mechanism when authorizing the original acquisition.

The distributor revokes an old key server-side and provisions its replacement
inside a new shell APK delivered through its ordinary installation/update mechanism.
After that APK update, the shell reads and uses the new key. Revoked-key requests
remain denied until a newly authorized APK is installed; Paravoid does not try to
recover by exchanging the revoked key for another one. This may temporarily stop
VPK downloads and does not promise a silent Android APK installation.

The installed APK is the credential source of truth. Any cached key must be
invalidated on APK replacement; app data/backup must not override it with an old
credential. Server authorization, including conditional/range requests and any
download delegation, must enforce revocation with explicitly defined timing.
Credential replacement does not itself require a new shell contract ID, but the
APK must still satisfy Android signing/update rules. Changed code, manifest or
pinned resources retain their normal compatibility requirements.

A missing, malformed, expired or revoked key MUST NOT silently downgrade to public
mode, reset trust or trigger a new login flow. Keep a usable verified local payload
and report update access as unavailable; without one, show shell-owned provisioning
or recovery status. Recovery may require obtaining a newly authorized shell from
the distributor. Reinstall/data-clear/backup-restore behavior must be tested:
clearing data or reinstalling an old APK cannot restore a revoked entitlement.

### Explicit security limitation

An APK-carried key is extractable and copyable by anyone possessing that APK.
Private storage after first launch does not make the original APK key confidential.
Signatures authenticate an issuer; they do not prevent copying a personalized APK.
This design supports transparent revocable entitlement, not copy-proof licensing.
Redact keys from logs, URLs, analytics and packaging reports. Any future one-time
exchange/device-key binding must specify copied-APK redemption races and recovery;
it cannot silently be assumed to solve them.

V1's selected offline policy is to retain/run an already verified compatible
payload when the server is unavailable or a download credential expires/is
revoked. Revocation stops authorized future downloads; it is not remote deletion
or DRM for code already delivered. Different execution-licensing requirements
would need a separate explicit specification. Never erase user data on auth failure.

## 7. Embedded and empty bootstrap; activation

The complete-profile plugin implements `bootstrap = 'embedded'` or `'empty'`,
as defined in V1.md (release acceptance remains tracked in RELEASE-READINESS.md):

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
successful work, or replay arbitrary privileged Intents after provisioning. Component
unavailability/error semantics are selected in V1.md and require device proof
before this mode ships. Arbitrary intents are not preserved for replay; disabling
every component is not an assumed solution.

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
- Public and key-protected delivery with identical integrity checks; unauthorized
  metadata/range/download/304 access, missing/wrong-app/revoked keys and redirects.
- Personalized APK signature verification and installed credential readback; old-key
  revocation followed by APK-provisioned replacement, stale caches/processes, copied
  APKs, credential stripping and data-clear/restore with a revoked embedded key.
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

## 9. Gates before freezing v1

The choices previously listed here are selected in [V1.md](V1.md), not left to a
store integration to invent. Remaining work is executable specification and proof:

1. Encode the selected field/layout rules as machine-readable schemas and shared
   positive/negative vectors; prove independent Python/Android agreement.
2. Review the cryptographic/trust/freshness implementation and documented threat
   limits; check the candidate APK block ID for collisions before wire freeze.
3. Prove production grant provisioning/readback/replacement, request authorization,
   cache/range behavior, credential revocation and corrupted-state repair.
4. Prove whole-app early loading, absent-component adapters, interprocess leases,
   interrupted activation and forward-only data-safe recovery on devices.
5. Implement the selected plugin API, baseline workflow, reports and automatic
   full packaging without downstream manual splitting.

Use small verified commits. The selected design is not a finished secure updater;
backend interoperability claims require frozen vectors and passing implementations.
