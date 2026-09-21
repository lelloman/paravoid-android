# Packaging-first roadmap

Agreed direction, 2026-09-21. This is planned work, not a list of implemented
capabilities. The [compatibility matrix](compatibility/README.md) records tested
behavior and limitations.

## Direction

The compatibility suite is broad enough to move from isolated exploration to
product implementation. Prioritize complete Gradle-plugin packaging, then use a
real downstream app to expose remaining integration gaps. Keep the existing suite
as regression coverage; do not require exhaustive Android compatibility before
starting this work.

Downstream integration remains an ordinary app with the generated packaging flavor
dimension, a fixed installed set of app/library Activity declarations, and an optional custom Application extending
`ParavoidAndroidApplication`. No Activity annotation, special Compose entry point,
manual resource split, or downstream bootstrap/packaging scripts.
The one-Activity design rule is superseded. Non-launcher app/library Activities
are now preserved and transformed, with real-library packaging checks. The single
launcher/alias restrictions and broader multi-Activity device coverage remain open.

## 1. Complete automatic packaging — next milestone

**Outcome:** the plugin builds a normal APK/AAB, a shell APK, and a standalone
complete payload. The same payload is embedded in the shell for this milestone.
An embedded first version lets us validate packaging independently of delivery.

Today the plugin moves app/dependency DEX into the payload, but resources, assets,
Java resources and native libraries still reside in the installed APK. The
[independent-resource probe](compatibility/resources/README.md) is a manually
constructed experiment, not the missing automatic implementation.

Implement in small, independently verified steps:

### 1a. Define the shell/payload contract

- Implement the [revised Activity contract](PACKAGING.md#activity-declarations-and-library-components):
  preserve app/library declarations in the shell while keeping implementations in
  the payload; generalize validation, single-destination metadata and launcher
  routing. Test multiple launchers, external entry, Activity results and restoration.
  Reject incompatible manifest/pinned-resource changes against an existing shell
  baseline instead of rejecting apps merely for having multiple Activities.
  First slice complete: multiple declarations and per-Activity hooks, verified
  with [AppAuth/Androidoscopy build artifacts](compatibility/library-activities/README.md).
  Broader launcher routing, contract diffs and device-flow verification remain.

The design baseline is now [defined in PACKAGING.md](PACKAGING.md): API 30+
complete packaging, pinned shell resources, a shared stable-ID namespace, and a
versioned shell compatibility contract. The same-package linking/early-loading
strategy still requires its focused implementation proof; fixture assertions are
not yet complete. The first [same-package fixture](compatibility/resource-split/README.md)
now verifies stable linking, a pinned-only installed table and early runtime
resource access; automatic resource ownership/pruning and the remaining gate
scenarios are still pending.

- Inventory app and dependency outputs from AGP; define which artifacts belong to
  the shell and which belong to the payload. Preserve ordinary normal packaging.
- Define the installed contract: package/component identities, permissions,
  manifest declarations, bootstrap resources and resources required by the
  installed manifest. Generate a machine-readable compatibility description and
  a readable packaging report. Do not silently treat shell-bound changes as
  payload-only updates.
- Specify resource package IDs, stable-ID ownership and a persistent ledger across
  builds/releases, including removals. Resolve references crossing the shell/payload
  boundary and avoid installed copies silently masking removed payload resources.
- Resolve the Android-version boundary: the resource-loader experiment uses public
  API 30+ APIs; existing code-only packaging supports API 28+, and installed native
  loading supports API 29+. Investigate support for complete packaging on older
  versions or explicitly require a higher minimum for that mode. Do not silently
  raise the existing minimum or claim an unimplemented fallback.
- Version the package format and identify its logical components: code, compiled
  resources, assets/Java resources, native libraries and metadata. Bind them to one
  coherent payload version and record shell/runtime compatibility. Concrete format
  choices follow investigation; this does not commit us to a server API or split
  algorithm yet.

**Exit:** a reviewed contract, explicit supported API range, and fixture assertions
for the intended contents of shell and payload.

### 1b. Package and load app/library resources automatically

- Integrate AGP/AAPT2 outputs into the plugin without a separately maintained
  downstream resource module. Cover generated resources and dependency resources,
  not just hand-authored app strings.
- Generate the shell's required resource subset and the payload resource artifact,
  with correct compiled references, themes, styles and stable IDs.
- Prepare payload resources early enough for Application construction and provider
  startup, as well as Activity inflation and configured contexts. Ensure each
  process initializes the same selected payload correctly.
- Test XML and Compose lookups, library themes/custom views, locale/night/density
  configurations, recreation and process death. With a fixed shell, cold-activate
  two local payload versions containing real code/resource changes; test resource
  additions/removals and incompatible-shell rejection. This remains a local test,
  not a production update API.

### 1c. Finish the remaining payload contents

- Move app/dependency assets and Java resources, including service descriptors,
  into the appropriate payload component and preserve their lookup semantics.
- Package ABI-specific native libraries and implement payload-native loading,
  including dependent libraries and cold component startup. Existing JNI tests
  validate installed libraries; they do not prove this new path.
- Embed the produced standalone payload unchanged for the bootstrap experiment.
  Publish clear Gradle task outputs and integrate with downstream build types and
  flavor dimensions. Verify incremental rebuilds, clean builds and artifact
  contents; fail clearly for unsupported configurations.

**Milestone acceptance:** one ordinary downstream-style app builds and runs in
both modes, with its implementation resources/assets/native libraries supplied by
the payload except for explicitly justified shell-bound content. No hand-written
splitting or loading code is required. Normal APK/AAB output still works. Artifact
inspection, negative controls and device tests verify the boundary; a successful
build alone is insufficient. Bootstrap changes cannot depend on fetching a payload
to discover that the installed shell is incompatible.

**Out of scope here:** networking, server authentication, update settings, package
retention UI and configuration-specific downloads. Preserve extension points for
them without implementing those systems.

## 2. Integrate a real downstream app

An early **DEX-only** Pezzottify integration now builds normal and shell phone/debug
APKs on its dedicated `codex/paravoid-integration` branch (AGP 9.0.0, Gradle 9.1.0,
Hilt 2.57.2). Its logged-out API-30 and API-36.1/x86_64 smoke gates pass startup/restart,
Compose login UI, rotation, callback routing and Room database integrity; Androidoscopy
initializes. The worktree's `android/PARAVOID.md`, `check-paravoid.py` and
`smoke-paravoid.py` record/reproduce the checks. This is not this milestone's exit:
complete packaging, authenticated workflows, library Activity screens, background
sync, playback and performance measurements remain pending. Normal builds retain
minSdk 24 through the API-only Application base; shell builds use minSdk 30 and
the default `.paravoid` identity with a separate OIDC callback registration.

- Select one representative existing app and integrate through the plugin's public
  contract, using an embedded complete payload initially.
- Compare normal and shell behavior in the app's real startup, navigation, storage,
  background work, dependency and resource paths.
- Convert failures into minimal regression cases; make narrow fixes or document
  concrete restrictions. Do not hide integration work in app-specific manual
  packaging scaffolding.
- Measure shell/payload size, cold-start time and memory to establish a baseline.

**Exit:** the selected app's agreed critical workflows pass in both modes, with
remaining restrictions documented. This proves the integration, not every app.

## 3. Add authenticated delivery and safe activation

The [store-agnostic distribution draft](DISTRIBUTION.md) now defines the design
boundary and a proposed v1 HTTP binding. Public delivery is supported in the target
contract; the other mode uses an app-scoped key provisioned by the distributor
inside the shell APK. The distributor revokes keys and delivers replacements
through shell APK updates; Paravoid does not renew or rotate keys itself.
There is no Paravoid OIDC login or required store-app handshake. VPK signature
verification is mandatory in both modes; APK-carried keys are not copy-proof.
Store catalogs, upload APIs and admin UI are outside Paravoid. Freeze the draft's
wire/security profiles with conformance vectors before claiming interoperability.

The [APK provisioning experiment](compatibility/provisioning/README.md) now
exercises public/key discovery and harmless downloads, APK personalization without
changing its signing identity, server revocation and APK-delivered replacement
credentials. Its [signed extension](compatibility/provisioning/SIGNED.md) checks
issuer-authenticated grants, signed discovery, replay/freshness and transfer
integrity using independent Python/Android implementations. These are isolated
fixtures, not production runtime/plugin support; complete signed VPKs, finalized
trust/provisioning policy and safe activation are still required.

- Add plugin/server configuration and produce the complete uploadable package.
  Define signing and verification before accepting externally supplied content.
  Retain the existing signing direction: product-shell signing authority, explicit
  certificate-rotation policy, and publisher signing private keys kept in release infrastructure.
- Authenticate metadata and every code/resource/asset/native component as one
  version. Verify size/format/compatibility and protect against tampering, replay,
  partial downloads and verification/loading races.
- Implement app-private staging, offline startup, first-launch availability and
  atomic activation on cold starts. Define cross-process version coordination;
  do not hot-swap classes in a running process.
- Support an explicit empty-shell option after proving payload-absent component
  startup and shell-owned download/auth/recovery UI. Keep embedded bootstrap as
  the packaging milestone default. Neither mode requires an installed store app;
  an empty shell may bootstrap any currently offered compatible signed VPK.
- Define startup health and recovery policy. A previous payload is a safe fallback
  only when persistent data is compatible: reverting DEX does not undo database
  migrations. Do not silently delete user data to make rollback succeed.

**Exit:** the real app can receive a verified update while the shell stays fixed;
failure/offline tests retain a usable compatible version or expose a clear recovery
state. Shell-incompatible changes require a shell update.

## 4. Add shell-owned control surfaces

Design these after packaging is working and delivery/activation contracts are
clear. Candidate controls, not yet finalized API/UI commitments:

- Update availability, checks, progress and update preferences.
- Current payload identity and compatibility/diagnostic information.
- Older-package retention, storage limits and cleanup.
- Selection/recovery actions for compatible retained versions, with explicit
  limitations when persistent data prevents rollback.

**Exit:** users can understand and control package management without downstream
apps implementing the shell's management UI. Cleanup cannot remove versions still
in use by running processes or required by the defined recovery policy.

## 5. Future: configuration-targeted distribution

An **AAB-like distribution model**, not a commitment to Google Play's AAB format:
upload one complete package, then deliver only the resource configurations and
native ABIs required by a device. Here, "resources" means Android resources—not
arbitrary files in `assets/`.

- Derive selectable artifacts from one coherent build, preserving resource IDs,
  references, defaults and code/resource compatibility.
- Define configuration selection and dependencies, including locale, density and
  ABI; authenticate the selected set and reject missing/mixed-version components.
- Handle configuration changes after installation, including newly selected
  languages, and define offline behavior when a needed component is unavailable.
- Keep full-package distribution as a baseline/control. Arbitrary asset targeting
  would be a separate explicitly configured feature, not inferred automatically.

**Exit:** targeted and full downloads behave equivalently on the covered device
configurations, with measured size savings and configuration-change tests. Until
then, packaging includes the full resource set.

## Release hardening and working discipline

Broader API/ABI and real-device coverage, release toolchains/shrinking, third-party
SDK compatibility, performance and distribution constraints remain release gates
to define for the supported product—not prerequisites for starting milestone 1.
Unsupported build modes must remain explicit rather than silently producing
incorrect artifacts.

For each implementation slice: test normal as the control, test shell behavior,
document requirements and limitations, and make small commits. Add focused probes
when a concrete risk or real-app failure warrants one. Do not restart an open-ended
compatibility exploration phase before finishing packaging.

**Immediate next task:** extend the passing same-package proof to ordinary AGP
app/library R integration and computed pinned-resource ownership, then wire it into
automatic packaging. Finish the [remaining contract-gate scenarios](PACKAGING.md#7-first-implementation-gate)
alongside that implementation before advertising complete packaging support.
