# Complete packaging contract

Design baseline, 2026-09-21. This specifies the next implementation; it does not
describe capabilities already shipped. The resource linking strategy below must
pass its implementation gate before complete packaging is advertised as supported.

[V1.md](V1.md) selects the build API, archive/signing profile, initial support
boundaries, activation/recovery policy and completion gates. Read it alongside this
resource/ownership design; selected API names are not yet implemented features.

## 1. Downstream contract and Android support

Keep the existing plugin-generated `normal` / `paravoidAndroid` flavors, ordinary
app/library Activities discovered from the merged manifest, and an optional
`ParavoidAndroidApplication` subclass. No new annotation, resource module,
bootstrap code or downstream packaging script is required. The agreed rule is
a fixed installed Activity contract, not one Activity; implementation still lags
this decision as detailed below.

The generated `paravoidAndroid` flavor defaults to `applicationIdSuffix '.paravoid'`;
normal variants keep their existing identity. Downstream builds can override the
suffix, clear it, or set a full `applicationId` and clear the suffix through the
ordinary flavor DSL (see [integration configuration](README.md#plugin-integration-in-the-example)).
The package identity belongs to the installed shell, not an updatable payload.
Package-dependent external registrations and hardcoded manifest identifiers remain
the downstream app's responsibility.

**Complete packaging initially requires minSdk 30 in the shell variant.** Use the
public ResourcesLoader/ResourcesProvider APIs, introduced in API 30; do not build
a hidden-API fallback into this milestone. Normal packaging keeps its own minSdk.
Existing DEX-only packaging remains available under its current API 28 minimum
(29 when installed native libraries are present). These are distinct capabilities:
the plugin must expose an explicit packaging selection during migration and reject
complete packaging below 30 rather than silently raising minSdk or falling back.
The target DSL is defined in [V1.md](V1.md#2-build-api-and-reproducible-outputs),
explicitly separate from today's working API.

API 30 and the current API 36.1 emulator are required device gates for complete
resource packaging. The bounded same-package fixture now passes on both, but the
complete automatic packaging gate remains pending. Shrinking and other currently rejected configurations stay rejected
until specifically implemented; this decision does not enable them.

## 2. Artifact ownership

| Installed shell | Versioned payload |
| --- | --- |
| Package identity, signing identity, merged manifest and component declarations | App and dependency DEX, including generated classes and transformed Application behavior |
| Runtime, bootstrap launcher and shell-owned code/resources | App/library compiled resources used by payload code |
| Resources required before payload loading or by another process, plus their dependency closure | App/dependency assets and Java resources, including service descriptors |
| Explicitly declared shell-native dependencies, if the bootstrap itself needs any | App/dependency native libraries for the packaged ABIs |
| Shell compatibility descriptor and initially one embedded complete payload | Payload metadata and component integrity/compatibility information |

The embedded payload is identical to the standalone output. Apart from embedding
that container, app implementation files must not also remain installed as loose
fallback copies. Inspect the final artifact to enforce this, not just task inputs.
Normal APK/AAB packaging continues through conventional AGP paths and does not
inherit shell resource pruning or the shell's resource-ID ledger.

### Activity declarations and library components

This decision supersedes the original one-Activity restriction. The plugin must
preserve every supported Activity declaration from the **merged manifest**, whether
owned by the app or contributed by an AAR. Their classes, including generated
superclasses and dependencies, belong in the payload unless they are Paravoid
bootstrap infrastructure. Android creates the real Activities through the payload
component factory; the shell is not an Activity lifecycle proxy.

Examples include AppAuth's `AuthorizationManagementActivity` and Androidoscopy's
dashboard/session Activities found during the Pezzottify audit. Their presence
alone should not require modifying the libraries or moving their code into shell
DEX. This is the intended integration contract, not a claim that those libraries
have passed a Paravoid device integration test.

| Change | Required artifact under the intended contract |
| --- | --- |
| Activity implementation, compatible dependency code or internal behavior | New payload; declaration and all other shell requirements must stay compatible |
| Unpinned layout/string/drawable used inside the payload process | New payload once complete resource packaging is implemented |
| Add, remove or rename an Activity, including a library-contributed one | New shell and matching payload |
| Change exported/enabled defaults, permissions, process, launch mode, task affinity, configuration flags, intent filters, deep links or manifest metadata | New shell and matching payload |
| Change manifest-referenced theme/icon/label or any other pinned resource/dependency | New shell and matching payload |

Requirements:

- Compare each payload's required declarations against the chosen shell baseline.
  A library upgrade is not inherently payload-safe: inspect its merged-manifest
  and pinned-resource changes as well as its code. Report differences explicitly.
- Keep every declared payload Activity implementation available and compatible
  with its installed declaration. Removing the class while leaving its declaration
  is not a valid payload-only way to remove an Activity.
- Preserve entry-point behavior: launcher destinations, external Intents, deep
  links, Activity results and task/back-stack semantics. Do not route every entry
  indiscriminately to a single main Activity or make a launcher visit a prerequisite
  for direct component creation. Resources/classes must be ready on cold entry.
- Manifest-defined capabilities remain immutable until reinstall/update of the
  shell. Ordinary Android runtime state (for example temporarily enabling an
  already declared component) is distinct from changing that installed contract;
  it cannot add a missing declaration and needs its own coverage.

**Current implementation:** the plugin preserves multiple Activity declarations
and transforms every declared payload Activity; shared inherited callbacks are
patched once. The [real library fixture](compatibility/library-activities/README.md)
passes normal/shell builds, lint and manifest/DEX placement checks for AppAuth and
Androidoscopy. It does not yet verify those SDK flows on a device.

**Remaining limitations:** aliases and anything other than one MAIN/LAUNCHER filter
are still rejected. The launcher metadata represents one default destination,
alongside a build-time inventory of all Activity classes. Multiple-launcher routing
and shell-baseline compatibility-diff validation are not implemented. Add device
coverage for multiple app/library Activities, result round trips, external cold
entry and process restoration. TV/LEANBACK routing and alias support need explicit implementation
and tests; they are not established by allowing multiple Activity classes. Custom
component-factory restrictions and Application integration requirements also remain.
Do not strip library Activities merely to make an integration appear to pass.

Independent resource packaging and external payload activation are still under
development. Today's embedded-payload build still requires APK replacement to
change its payload; the table defines compatibility boundaries for the target
architecture, not an already available update mechanism.

## 3. Resource boundary: pinned versus movable

The plugin computes a **pinned resource set** from:

1. Shell bootstrap resources.
2. Every resource reference in the final installed manifest, including dependency
   contributions, labels/icons, themes, provider metadata and XML configuration.
3. Explicitly declared resources that consumers outside the payload process load
   by package/resource ID: for example notification icons, RemoteViews layouts,
   widget resources or externally consumed `android.resource` URIs.

Include all configurations and the transitive dependency closure: style parents,
attributes, referenced values, XML references and referenced files. A manifest
theme can therefore pin more resources than its name suggests. The packaging
report must explain each inclusion and its reference chain. Unresolved references
must fail the build, not silently widen the set to the entire app.

Manifest/bootstrap roots are automatic. For runtime-selected external resource IDs,
the plugin cannot reliably infer all uses through reflection or third-party code.
Provide declarative additional pinned roots in plugin configuration (libraries may
eventually contribute them). This is an explicit integration requirement, not a
promise of universal static detection. The plugin still performs all splitting;
the developer does not create separate resource modules or copy files manually.

Pinned IDs **and values/files across all configurations are frozen for a shell
generation**. Adding, removing or changing a pinned resource requires a new shell.
Unpinned resources may be added, changed or removed through a payload update.
This is conservative: an installed Activity theme may be frozen even when some
of its values are mostly used after loading. A future bootstrap-theme indirection
can relax that boundary; it is not silently assumed here.

External resource consumers do not inherit our ResourcesLoader. In particular,
moving all app resources into a payload cannot automatically make new resource
IDs usable by System UI or another package. Existing notification tests using
installed resources must be rerun against the new boundary.

## 4. IDs and linking strategy

**Selected strategy to prove:** one logical application resource package, using
the ordinary application package ID `0x7f`; framework IDs remain unchanged.
Do not adopt the experiment's separate `0x80` namespace as the production contract.

- Link the complete merged app/library resource universe against one stable-ID
  ledger. Generate app/library R classes, styleable arrays and compiled XML from
  this same mapping; do not repair mismatched IDs with a blanket DEX integer rewrite.
- Derive the installed pinned-only table and a complete payload resource APK from
  that mapping. The payload may include identical copies of pinned entries for
  linking simplicity, but cannot override their meaning. The build validates
  equality and the loader validates compatibility before attachment.
- Both artifacts use the same package/name/ID mappings. Runtime precedence must
  resolve movable entries from the selected payload. No older payload is layered
  underneath it, and the installed table contains no movable values to resurrect
  a removed entry. Reserved IDs without values are not fallback resources.
- Select one immutable resource provider set per process startup. Do not mutate
  a live ResourcesLoader to switch app versions.

The original probe only proves separate-package loading. The new
[same-package fixture](compatibility/resource-split/README.md) now proves a bounded
`0x7f` split, static-library R/styleable linking and early resource access on
API 30 and 36.1. Its inputs are hand-selected and its loading hook is fixture-only; it
does not prove automatic dependency splitting or the entire gate below. Complete
that gate before locking the strategy into a released format. If it fails, revise
this document explicitly; do not quietly substitute unsupported APIs or require
a manual downstream split.

### Ledger lifecycle

Maintain a persistent ledger per product/variant release lineage, including shell
runtime resources and library symbols. Record resource type/name, numeric ID and
live/retired status. Never reuse a retired entry or type ID within that lineage;
resource removal leaves a tombstone. A rename is removal plus a new allocation.

The plugin generates allocations and emits the next ledger as a build artifact;
developers do not assign IDs. Preserve the accepted ledger in source control or
release artifacts and supply it to subsequent builds. An explicit promotion step
may update that baseline; ordinary compilation must not silently modify tracked
source files. Concurrent/branched releases must reconcile against the accepted
ledger before publication, not independently publish conflicting allocations.

Without a baseline, build a **new shell generation**, never claim compatibility
with an old one. Payload compatibility compares the shell's baseline and existing
mappings, while allowing append-only allocations; requiring equality of the entire
new ledger hash would incorrectly reject resource additions. Record the full new
ledger digest for integrity and future builds separately.

**Implemented first slice:** the plugin's optional `paravoid.baselineDirectory`
and `export<Variant>ParavoidResourceLedger` task now preserve exact linked names,
entry/type ID reservations and tombstones using public AGP 8.13.2 hooks. See the
[working baseline workflow](README.md#resource-id-baseline-first-complete-packaging-implementation-slice).
This observes the currently installed resource table. The separate
[pinned-resource analyzer/check](README.md#pinned-resource-analysis-and-boundary-checks)
now computes manifest/explicit closure and detects installed-manifest/pinned-content
changes against a reviewed snapshot. The
[resource-container task](README.md#generate-the-two-resource-containers) now prunes
a linked table into a pinned-only archive and exports the complete resource/assets
archive, validating binary round trips without renumbering IDs. It does not yet
replace the installed table or attach the payload resources at runtime. Complete
shell-contract checking remains unfinished; the explicit analysis tasks are not
automatic assemble gates.
A ledger or resource-boundary check alone is not a complete shell approval.

## 5. Shell compatibility contract

Generate a canonical descriptor of installed requirements: package identity,
min/target SDK, component names/processes/attributes, permissions/features,
manifest metadata, runtime ABI, supported payload format/capabilities, pinned
resource mappings/content and baseline ledger identity. Normalize build paths and
irrelevant serialization order. Initially treat changes to the installed contract
conservatively as requiring a new shell, including changes to transformed
Application/component-factory metadata that currently lives in the manifest.

Its digest is the **shell contract ID**. It excludes embedded payload bytes and
payload version, avoiding a circular hash and allowing the same contract to host
multiple payload releases. It is not the APK hash, an APK versionCode, or proof of
authenticity. Signing identity and authorized signing policy are validated separately.
For future key-authenticated distribution, per-grant credential bytes provisioned
inside the APK are also excluded from this digest; authentication mode, provisioning
format and trusted issuer/service policy remain installed requirements. Personalized
credentials cannot change code, resources or payload trust roots without a new
contract. See [distribution authentication](DISTRIBUTION.md#6-public-or-distributor-provisioned-key-authentication).

Every payload names its required shell contract ID. Initially require an exact
match plus supported runtime/format and device requirements. Do not infer safety
from matching package names or try best-effort execution on mismatch. A later
compatibility-range model needs its own proof. Database compatibility remains a
separate activation concern; passing this contract does not make rollback safe.

Against a supplied shell baseline, fail payload-only packaging on a mismatch and
explain the offending manifest/resource change and why a shell rebuild is needed.
Building a new shell is allowed to establish a new contract; the plugin must not
silently relabel an incompatible payload as compatible with the previous one.

## 6. Container and startup contract

The [store-agnostic distribution draft](DISTRIBUTION.md) calls the future outer
container a **VPK** and defines discovery, optional access authentication, mandatory
release verification and embedded/empty bootstrap requirements. Its wire and
security profiles are not frozen; today's DEX-only `module.zip` is not a VPK.
The embedded-first packaging milestone below remains unchanged. Empty-shell output
is a later explicit option gated on safe startup without any payload.

Use a new versioned **outer complete-payload format**, distinct from the existing
DEX-only `module.zip` formats 1/2. Initially it contains a contiguous DEX set, one
resource APK (including ordinary assets), Java-resource content, native libraries
grouped by ABI, and metadata. [V1.md](V1.md#3-complete-package-format) selects exact
paths, serialization and limits, subject to executable conformance before release.

Metadata identifies format and payload version, shell contract, SDK/ABI requirements,
ledger identity, and an inventory of component paths, sizes and digests. Reject
duplicate/unsafe paths, missing/unexpected components, conflicting reserved shell
names, unsupported versions and resource/ABI mismatches. Establish explicit archive
and expansion limits in the reader before accepting complete containers. Hashes
under the installed APK's signature suffice for the existing embedded experiment,
but v1 requires a signed release manifest for embedded and downloaded VPKs alike.

Load in this order, independently in each app process:

1. Select and validate the entire coherent payload, not separate code/resource
   versions. Stage read-only resource/native files in private version-scoped storage
   where required; concurrent processes must not observe incomplete staging.
2. Attach resources/assets to the base/application contexts before constructing
   payload Application behavior, component factories or providers. Set up the native
   search path before payload class initialization can call `System.loadLibrary`.
3. Create/publish the payload class loader and construct payload Application
   behavior. Preserve Android's provider/onCreate ordering.
4. Ensure Activities, Services, providers and derived/configuration contexts see the
   selected resources; explicitly test framework-created themes and early lookups.

Do not attach only in Activity.onCreate or user Application.onCreate. Failure must
prevent partially initialized payload components from running. Shell bootstrap/error
resources must remain usable without a valid payload. Java-resource lookup and
ServiceLoader semantics must be preserved by implementation, not assumed to follow
automatically from loading DEX. Native-library dependencies/ABI behavior likewise
require the payload-native tests described in the roadmap.

## 7. First implementation gate

Before production automatic splitting, build one bounded normal/shell fixture:

- App plus an Android library, generated R/styleables, XML theme/layout and Compose
  lookup, using the same `0x7f` mapping with a pinned-only installed table.
- Verify pinned values with no payload; verify movable values are absent from the
  installed table. Demonstrate a real system-consumed pinned notification resource.
- Cold-start payload A → B → A against an unchanged shell; B changes a value, adds
  a resource and removes another. Existing IDs stay fixed; removed values cannot
  fall back to A or the installed APK. This is a controlled resource experiment,
  not permission for production updater downgrades or database rollback.
- Prove early Application/provider access, configuration contexts, recreation,
  process death and a named worker on API 30 and API 36.1.
- Reject pinned-content/manifest changes, ID reuse, a wrong shell contract and
  missing/mixed components. Verify both clean builds and incremental rebuilds.

Then wire the proven linking/pruning approach into AGP 8.13.2, the pinned toolchain.
Inventory supported artifact hooks before selecting a table-pruning/relinking
implementation; do not assume MERGED_RES exposes a ready-to-use closed resource
graph or depend silently on internal task names. Wider AGP support comes later.
Milestone 1a's fixture assertions are only complete when these tests exist and pass;
this document completes the design portion, not that executable gate.

Future configuration-targeted delivery must derive selected resource/ABI components
from the same ledger and coherent build. Initially include all configurations and
packaged ABIs; no server selection, split downloading or update controls are added.

## References

- [Public resource loading APIs, API 30+](https://developer.android.com/reference/android/content/res/loader/package-summary)
- [ResourcesLoader precedence and sharing](https://developer.android.com/reference/android/content/res/loader/ResourcesLoader)
- [AAPT2 stable-ID inputs and emitted mappings](https://developer.android.com/tools/aapt2)
- [RemoteViews and cross-process resource inflation](https://developer.android.com/reference/android/widget/RemoteViews)
- [Resource-backed Icon references](https://developer.android.com/reference/android/graphics/drawable/Icon)
- [Current resource experiment](compatibility/resources/README.md)
