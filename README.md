# Paravoid Android

Paravoid Android explores a native Android application with two release cycles: an
installed shell that changes infrequently, and signed application modules that
the shell downloads and runs without reinstalling its APK.

The same application implementation should also build as a traditional,
self-contained Android APK or AAB. Both modes are intended to render native UI,
including Jetpack Compose.

## Agreed downstream architecture

The downstream developer keeps an ordinary Android application project. The
intended integration contract is:

- Apply the Paravoid Android Gradle plugin and configure the update server address,
  authentication if needed, and packaging settings.
- Keep ordinary app and library Activities declared in the merged manifest. Their
  installed declarations are fixed for a shell generation; their implementations
  belong in the payload. No Paravoid Android Activity superclass, annotation, or
  special Compose entry function is required. This supersedes the one-Activity
  design rule; non-launcher library Activities are now accepted, with remaining
  routing limits described below.
- When custom Application behavior is needed, extend `ParavoidAndroidApplication`.
  The plugin discovers this class from the merged manifest; no Application
  annotation or separately configured initializer is required.
- Select normal or Paravoid Android packaging through a flavor dimension generated
  by the plugin, alongside the app's build types and existing flavor dimensions.

The plugin packages the entire application implementation, including its
dependencies and eventually its resources and assets. Downstream developers
should not manually split their project into implementation, shell, and standalone
modules, implement `AppEntry`, or write bootstrap code.

| Packaging mode | Intended outputs and behavior |
| --- | --- |
| Normal packaging | Conventional APK/AAB containing the app; the user's Application subclass is the actual Android Application |
| Paravoid Android packaging | Generated shell APK/AAB plus a separately packaged application payload; the shell owns the actual Android Application and bootstrap launcher |

The example now uses plugin `com.lelloman.paravoid`, dimension `paravoidPackaging`, and
flavors `normal` and `paravoidAndroid`. Server/authentication configuration and external
delivery remain future work; this experiment uses an embedded payload.

The [distribution specification draft](DISTRIBUTION.md) defines the store-agnostic
direction: signed VPKs, compatible-release discovery/download, public access or
key-based authentication provisioned by the distributor inside the shell APK,
and embedded or empty-shell bootstrap. Updates require no separate distribution
login or installed store app. No particular store or identity provider is required.
APK-carried keys are copyable; this is not copy-proof licensing.
The distributor can revoke a key; restoring update access requires a new key
provisioned through a shell APK update, not Paravoid-managed key rotation.
The [v1 implementation contract](V1.md) now selects the build API, complete VPK
format, signing/authentication, cold activation, empty-shell behavior, recovery
and minimum controls, with an ordered completion checklist. These are implementation
requirements, not shipped plugin/runtime features; wire freeze still requires
cross-implementation vectors and security review.

### Application behavior

The downstream source uses this shape (the current example is Java):

```kotlin
class MyApplication : ParavoidAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        // Application initialization
    }
}
```

In normal packaging, this class is the app's Android Application. In Paravoid Android
packaging, the plugin transforms the user-defined behavior into a separate
payload class that is not an Android Application. The shell's Application calls
that behavior once the selected payload is ready, before creating the user
Activity. There is only one actual Android Application per process.

The familiar downstream API is intentional: packaging differences and the
transformation burden belong to Paravoid Android. A separate `ParavoidAppInitializer`
interface is not the chosen public API.

The initial transformation remaps `ParavoidAndroidApplication` to a shell-backed
`PayloadApplication` ContextWrapper throughout payload bytecode. It preserves fields
and supports `onCreate`, `super.onCreate()`, and inherited Context access. The shell
forwards configuration and memory callbacks. The custom class must directly or indirectly extend
`ParavoidAndroidApplication` and have a public no-argument constructor.

Application-specific APIs beyond this subset, custom Application casts, object
identity, and broader dependency injection still need compatibility work. Provider
startup ordering and the optional Hilt integration are tested below. Code must not
pass the transformed object to APIs requiring an actual Android
Application. Arbitrary Application behavior is not yet guaranteed to work.

### Activity startup and installed manifest

**Agreed architecture:** preserve all app- and dependency-contributed Activity
declarations in the generated shell. Load their implementation classes from the
payload through Android component creation. A library Activity does not need its
implementation copied into the shell merely because its declaration is installed.
The constraint is a **fixed installed manifest contract**, not a single Activity.

Adding, removing or renaming an Activity, changing its manifest attributes,
intent filters or metadata, or changing its pinned resources requires a new shell.
Compatible code and movable resource changes may belong to a new payload instead.
See [the Activity compatibility contract](PACKAGING.md#activity-declarations-and-library-components)
for examples, requirements and implementation gaps. Resource updates and external
payload delivery are still planned features, not current production capabilities.

**Current implementation:** the generated shell preserves all declared app/library
Activities and adds Paravoid Android's `LauncherActivity`. It requires exactly one
MAIN/LAUNCHER filter, discovers its target, and assigns that filter to the
bootstrap Activity and preserves the user Activity's component identity and
required manifest configuration. During `attachBaseContext`, the shell prepares
the embedded payload and constructs the transformed Application. Android then
initializes providers; only in the shell's `onCreate` does it invoke the user's
`onCreate`. Before user construction and provider initialization, the shell sets
the initialization thread's context classloader to the payload loader, enabling
default library discovery there and on newly created threads that inherit it.
This does not replace context loaders on unrelated existing threads. The launcher then starts
the user Activity through Android and finishes itself. Android manages the real
user Activity's lifecycle; Activity
method extraction and lifecycle forwarding are not part of this design.

The current validator accepts multiple declared Activities, including dependency
contributions, but still requires one MAIN/LAUNCHER filter and rejects aliases.
All declared payload Activities receive loader and saved-state hooks. The
[library fixture](compatibility/library-activities/README.md) verifies real AppAuth
and Androidoscopy manifest preservation and payload class placement. This is not
device verification of their flows. Multiple launchers, aliases, TV entry and
multi-Activity restoration/results still need implementation or runtime coverage.

Loading a DEX class is not sufficient to make Android instantiate an Activity.
The runtime must integrate payload class loading with Android component creation;
the example uses `AppComponentFactory` on API 28+. The shell initializes the embedded
payload during Application startup, so direct Activity creation does not depend on
visiting the launcher. The plugin prepares launch Intent extras and saved-state
Bundles with the payload loader before Activity `onCreate`; the OS probe covers
cold notification delivery of a payload Parcelable. The Compose probe tests navigation and state
restoration after process death. The language probe additionally covers generated
Parcelable and Serializable saved-state objects. Other object graphs, state across
payload updates and downloaded payloads still need explicit coverage.

Saved callback state is nested in a platform Bundle until the pre-`onCreate`
hook restores it with the payload loader. This protects API 28's eager framework
reads, which happen before user `onCreate`; state added by the framework after the
save callback remains accessible at the outer level. See the [API 28 baseline](compatibility/API28.md)
for evidence, the reserved envelope key and migration limits.

Manifest-declared payload Services also receive a defining-loader `getClassLoader`
override unless their hierarchy already provides one. This lets Android decode
payload Parcelables in service Intents; the Binder probe covers cold bind,
explicit rebind and reattachment after service process death.

The shell retains the installed manifest, permissions, bootstrap code, and
resources required by that manifest and bootstrap UI. Payload updates cannot
dynamically add installed component declarations or permissions. Declared services,
receivers and providers are preserved and instantiated through the payload loader.
The factory delegates to AndroidX `CoreComponentFactory` when present, including
its component wrapping. Arbitrary custom factories remain unsupported because
their Application/classloader hooks need a separate contract. Bounded named-worker
and Direct Boot probes are recorded in the compatibility matrix; isolated-process
components and arbitrary combinations remain unvalidated. The integration contract above
does not imply support for every existing Android app or library.

## Current example and limits

The [compatibility matrix](compatibility/README.md) tracks verified scenarios and
untested areas. The [language probe](compatibility/language/README.md) adds Kotlin
Serialization/Parcelize plugins, reflection, dynamic proxies and default library
discovery, tested in both packaging modes before and after process death.
The [Views probe](compatibility/views/README.md) covers generated bindings, custom
XML Views, fragment restoration and inflation from Application/configuration contexts.
Configuration contexts from the shell Application and default Activity implementation
retain the payload loader; explicit Activity overrides are preserved, and other
Context factories are not yet covered.
The [JNI probe](compatibility/jni/README.md) verifies native dependencies and callbacks
with APK-backed and extracted libraries. Native-bearing shell apps require API 29+;
their native binaries remain installed APK content, not independently updated payloads.
The [API 29 boundary suite](compatibility/API29.md) verifies Compose/Hilt lifecycle
restoration and both JNI storage modes at that minimum API.
The [network probe](compatibility/network/README.md) covers Retrofit/OkHttp,
Gson/Moshi adapters, KSP, cancellation, caching and local HTTPS verification.
Its pinned Moshi/KSP2 generated-qualifier limitation is explicitly documented;
ordinary generated models and reflective qualifiers are tested separately.
The [Hilt WorkManager probe](compatibility/hilt-work/README.md) verifies injected
cold workers and Room persistence with lazy Application configuration. Add the
optional [paravoid-work plugin](paravoid-work/README.md) for `Configuration.Provider`
discovery in shell mode; it supports WorkManager 2.10.1 independently of Hilt and
needs no manual initialization. The Hilt plugin alone does not adapt that lookup.

`sample-app` is one Android app with a custom `SampleApplication`, an ordinary
`MainActivity`, and a native counter screen. Both generated packaging modes use
the same source. The custom Application exercises a field, `super.onCreate()`,
shared preferences, and application-context access. The screen checks that user
initialization happened exactly once and retains its counter across recreation.

The screen now inflates an XML layout and displays results from ordinary Android
and Java resource APIs. `sample-library` is a conventional Java dependency used to
exercise dependency classes, service discovery, and dependency resources; it is
not a required downstream app/shell split.

| Exercised content | Location in Paravoid mode | Verification |
| --- | --- | --- |
| App/dependency classes, enums, generic/nested classes, interfaces, lambdas, runtime annotations | Embedded DEX | Execution, reflection, loader identity, absence from shell class loader |
| Parcelable with anonymous Creator | Embedded DEX | Parcel round-trip with an explicit payload class loader |
| XML layout, theme, vector drawable, strings/plurals/arrays, numbers, booleans, dimensions, colors, raw UTF-8 file | Installed APK resources | Real screen inflation, theme lookup, value reads, English/Italian and day/night contexts |
| Nested JSON/text assets | Installed APK assets | UTF-8 reads, JSON parsing, directory listing, missing-file behavior |
| App/dependency Java properties and service descriptor | Installed APK Java resources | Class resource streams and ServiceLoader with an explicit class loader |
| Manifest receiver, service and provider | Embedded DEX; declarations in installed manifest | Receiver/service initialization and loader identity; provider runs before user Application `onCreate` |

These tests demonstrate that payload code can use installed resources. They do
not demonstrate independently updatable resources: `module.zip` still contains
only metadata and DEX. A packaging regression test checks APK/AAB locations and
proves an asset edit changes the APK while leaving the DEX payload unchanged.
Fonts, bitmap/audio/video assets, and native/JNI dependencies are not covered by
this sample expansion. Separate language and Views probes cover selected custom
Parcelable state and XML Views, not arbitrary object graphs or widgets.

Paravoid packaging moves application/dependency classes into an embedded DEX payload;
only Paravoid Android infrastructure remains in the shell's ordinary DEX. Normal
packaging uses ordinary Android classes. The main sample needs no server or network
permission; the network fixture declares INTERNET and its own network security policy.

This remains an entry-point and code-packaging experiment:

- Resources, assets, Java resources, and native libraries follow ordinary AGP
  packaging into the installed APK. They are not independently updatable yet.
- Native-bearing shells use installed native-library search paths on API 29+.
  Shell APK/AAB builds reject minSdk below 29 when AGP's merged native output
  contains libraries, including transitive dependencies. The error lists library
  paths; set `minSdk 29` on the `paravoidAndroid` flavor or in `defaultConfig`.
  Native-library exclusions are respected; the merged-artifact check conservatively
  includes all ABIs, even those filtered out later by `ndk.abiFilters`.
  Normal packaging is unaffected, and code-only shells retain API 28+. The runtime
  guard remains as defense in depth.
- The main sample uses Java and Android Views; a separate Compose/Hilt/Navigation
  fixture verifies Kotlin UI and process-death state restoration.
- Multiple launcher filters, Activity aliases, and component factories other than the
  platform default or AndroidX `CoreComponentFactory` are rejected in Paravoid mode.
  Additional non-launcher app/library Activities are accepted. Alias support and
  arbitrary custom component factories are not implied by the revised design.
- Unsupported Application inheritance, shrinking and core library desugaring are
  rejected. Multi-DEX payloads are supported within the bundle limits below.
  Runtime/API package names are reserved for
  shell infrastructure.
- Downloading, independent payload signatures, activation, and rollback are not
  implemented. Changing the embedded payload still requires reinstalling the shell.

## Build and run

Requirements: JDK 17+ (tested with 21), Android SDK platform 36 and Build Tools
35.0.0, and Android 9 / API 28+ for the example. The wrapper pins Gradle 8.13 and
the plugin uses AGP 8.13.2. Build dependencies may require network access; the main
sample runs offline. The network fixture starts its own local servers. Set
`ANDROID_HOME` or an ignored `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

```sh
./gradlew :sample-app:assembleNormalDebug :sample-app:assembleParavoidAndroidDebug
adb install -r sample-app/build/outputs/apk/normal/debug/sample-app-normal-debug.apk
adb install -r sample-app/build/outputs/apk/paravoidAndroid/debug/sample-app-paravoidAndroid-debug.apk
```

Both launcher entries are labeled **Paravoid Resource Example**. Paravoid mode displays
`InMemoryDexClassLoader`; normal mode displays its ordinary app class loader.
Both show initialization count 1. The sample's `.normal` and `.paravoid` application
ID suffixes allow coexistence, with separate data and counters.

The payload is `sample-app/build/outputs/paravoid/paravoidAndroidDebug/module.zip`, also
embedded at `assets/paravoid/module.zip` inside the shell APK.

```sh
./gradlew :sample-app:assembleNormalRelease :sample-app:assembleParavoidAndroidRelease \
  :sample-app:bundleNormalRelease :sample-app:bundleParavoidAndroidRelease
```

Release signing remains the downstream app's responsibility.

## Plugin integration in the example

The repository includes `paravoid-gradle-plugin` through `pluginManagement.includeBuild`.
Runtime projects are consumed from source; Maven publication is future work.

```groovy
plugins { id 'com.lelloman.paravoid' }
android {
    namespace 'example.product'
    compileSdk 36
    defaultConfig {
        applicationId 'example.product'
        minSdk 28
        targetSdk 36
        versionCode 1
        versionName '1.0'
    }
}
dependencies {
    implementation project(':paravoid-api')
    paravoidAndroidImplementation project(':paravoid-runtime')
}
```

The plugin applies `com.android.application` and generates the packaging flavors.
The API library (including `ParavoidAndroidApplication`, whose class name is
unchanged) supports minSdk 24. Only shell variants need the runtime dependency;
this avoids imposing its minimum SDK on normal builds. Set a higher minSdk on
the generated `paravoidAndroid` flavor when the normal app supports older devices.
Declare the Application and Activity in the ordinary manifest; no entry-point
configuration is needed. With no custom Application, user initialization is skipped.
By default, `paravoidAndroid` adds `.paravoid` to the application ID; normal
packaging keeps its existing ID. For example, `example.product` becomes
`example.product.paravoid`, allowing both apps to coexist with separate data.
Configure the generated flavor using the ordinary Android DSL:

```groovy
android {
    productFlavors {
        paravoidAndroid {
            applicationIdSuffix '.sandbox' // Override the default .paravoid.
            // For a full custom ID, set BOTH instead:
            // applicationId 'example.separateapp'
            // applicationIdSuffix ''
        }
    }
}
```

An empty suffix alone opts into the original application ID. Other flavor and
build-type suffixes still compose according to AGP rules; `namespace` and class
names do not change. The sample additionally uses `.normal` for its comparison app.
Use `${applicationId}` for manifest provider authorities and other per-install
identifiers. Hardcoded OAuth redirect schemes, API registrations and similar
package-dependent settings are not rewritten automatically; configure those for
the chosen ID. Sharing an ID does not permit side-by-side installation: a
compatible signing identity is required to update the existing installation.

For Paravoid variants, the plugin consumes AGP's scoped classes, transforms the
Application base with ASM, compiles payload classes through D8, and embeds the
bundle through generated assets. Its manifest transformation installs the shell
Application, launcher, and component factory. Normal variants retain ordinary
Android packaging.

### Resource ID baseline (first complete-packaging implementation slice)

The plugin can now export exact linked resource IDs from a standalone shell APK
and reuse a reviewed baseline through AGP 8.13.2's public per-variant AAPT2 options:

```sh
./gradlew :app:exportParavoidAndroidDebugParavoidResourceLedger
```

This builds the shell and writes `resource-ledger.json` and diagnostic
`stable-ids.txt` under
`app/build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/`.
Without a baseline, this is a new allocation, not a claim of compatibility with
an existing shell. Review and explicitly copy the JSON into
`app/paravoid/baseline/paravoidAndroidDebug/resource-ledger.json`, then configure:

```groovy
paravoid {
    baselineDirectory = layout.projectDirectory.dir('paravoid/baseline')
}
```

Each shell variant reads `<baselineDirectory>/<variant>/resource-ledger.json`;
when configured, a missing/invalid/wrong-application baseline is an error. Normal
variants never consume it. Exporting another candidate does not overwrite the
accepted file. Review/promote each published ledger, including added allocations,
so later builds cannot reuse IDs allocated on another release branch.

Removed names remain reserved tombstones; reintroducing the same name restores its
original ID. Changed/reused entry or type IDs fail validation. Names come from the
linked resource table, not Java R names: `style/Theme.App` stays dotted. App,
Android-library and generated resources are included; styleable arrays are not
separate resource IDs. Baseline-only edits invalidate linking, and clean/incremental
builds are covered by integration tests. Split-APK ledger export is not supported.

This does **not** yet split resources out of the installed APK or validate the
complete shell contract. Pinned-resource analysis is now available separately below;
neither feature is a complete VPK or update implementation.
Do not also supply a manual `--stable-ids` option when configuring this baseline.

### Pinned-resource analysis and boundary checks

```groovy
paravoid {
    // Additional roots used outside the payload process; exact local type/name.
    pinnedResources = ['drawable/notification_icon', 'layout/widget']
}
```

```sh
./gradlew :app:analyzeParavoidAndroidDebugParavoidResources
```

The analyzer follows typed references from the final installed manifest, including
library contributions, plus the explicit roots. Style parents/keys, theme
attributes, arrays/plurals and compiled XML dependencies are followed through every
configuration. Framework references stay outside the app graph. Strings or integer
values that happen to resemble resource IDs are not treated as references.

It writes `baseline-candidate/resource-boundary.json` and
`resource-boundary-report.txt` beside the ledger outputs. The report separates
pinned/movable resources and explains inclusion chains. Fingerprints cover all
values/configurations and the original bytes of referenced files; source metadata
is excluded from protobuf value fingerprints. Compiled file changes are treated
conservatively, even if a visual result happens to look identical.

For an existing shell, review and copy the boundary JSON alongside its accepted
ledger in `<baselineDirectory>/<variant>/`, then run:

```sh
./gradlew :app:checkParavoidAndroidDebugParavoidResourceBoundary
```

The check rejects changed installed manifests and added/removed/changed pinned
resources, writes `resource-boundary-check.txt`, and never changes accepted files.
APK versionCode alone is excluded; movable value changes are allowed. Keep the
accepted boundary fixed until issuing a new shell; evolving the resource ID ledger
does not authorize changing that boundary.

These are **explicit analysis/check tasks**, not yet gates attached to ordinary
assemble or complete-payload publication. They do not prune resources, approve
runtime/trust/native compatibility, or produce the complete v1 shell contract ID.
Current shell bootstrap uses no resource files of its own; future non-manifest
bootstrap resources must become plugin-owned roots before resource splitting ships.
Runtime-selected external resources still require explicit roots; reflection and
dynamic theme choices cannot be inferred universally.

The implementation is pinned to AGP 8.13.2/AAPT2 protobufs and standalone APKs.
Missing roots/files, unresolved app references, dynamic resource packages,
runtime-overlay tables and unsupported protobuf fields fail analysis rather than
silently returning an incomplete pinned set. These analysis restrictions do not
change existing normal or DEX-only APK assembly. The structured format is defined
by [AAPT2's resource schema](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto).

### Generate the two resource containers

```sh
./gradlew :app:splitParavoidAndroidDebugParavoidResources
```

This explicit task consumes the ordinary linked shell-variant APK and automatically
produces these files under `build/outputs/paravoid/<variant>/resources/`:

- `shell-resources.apk`: the compiled manifest, pinned-only resource table and
  pinned files, including every configuration and transitive dependency.
- `payload-resources.apk`: the complete original resource table, referenced resource
  files and app/library assets. Identical pinned resources remain in this full set.
- `split-report.json`: resource/file ownership, asset paths and baseline-check status.

These are **unsigned resource containers, not installable apps or complete VPKs**.
The ordinary assemble APK is neither rewritten nor stripped by this split task. A dedicated
[device gate](compatibility/automatic-resources/README.md) assembles a test-only
shell and attaches the generated resources early. The production counterpart is
the explicit signed embedded-resource shell task described below.

No downstream resource module or manually selected resource folders are needed.
The existing `pinnedResources` declarations and computed graph drive the subset.
When `baselineDirectory` is configured, splitting also requires and checks that
variant's accepted `resource-boundary.json`; incompatible changes fail before new
outputs are published. Without a baseline this prepares a new shell generation.

The shell table is filtered without renumbering IDs. Original compiled XML/images
are copied byte-for-byte, and the payload's entire table is copied unchanged.
Both containers are aligned and round-tripped through AAPT2: IDs, all configuration
values, file fingerprints and manifest identity must survive, and the shell must
contain no extra resource entries. App/library assets are payload-only; their bytes
and compression modes are preserved. Neither container includes installed DEX,
native libraries, Java-resource entries, APK signatures or the embedded
`assets/paravoid/module.zip`. Those belong to other packaging stages.

Tests cover app/library resources, XML dependencies, whole-type removal, unchanged
shell output across movable-value updates, removed payload resources, stable IDs,
assets, empty tables and reproducible clean/incremental output. The generated pair
also passes 19 device stages each on API 30 and 36.1: normal control, pinned-only
shell, A/B/A resources/assets, early Application/provider and named-process access,
recreation, day/night changes and rejected corrupt/writable archives. Installed
code stays fixed in this fixture; this is not yet full-VPK update validation.

Current limits: standalone APKs and the analyzer's supported resource profile.
Unknown reserved `assets/paravoid/` entries or unclassified `res/` files fail rather
than silently disappear. Signing the full
VPK, full shell-contract validation and complete-packaging assemble integration are
still unfinished. See [AAPT2](https://developer.android.com/tools/aapt2) and
[zipalign](https://developer.android.com/tools/zipalign) for the underlying tools.

### Build a signed embedded-resource shell

```sh
./gradlew :app:packageParavoidAndroidDebugParavoidResourceShell
```

This explicit production task builds `outputs/paravoid/<variant>/resource-shell.apk`.
It consumes the validated split outputs, replaces the installed table/files with
the pinned subset, removes ordinary installed assets, embeds the complete resource
container and its SHA-256 identity, aligns the APK and signs/verifies it with the
build type's `signingConfig`. The certificate must match the input APK. Normal
and existing DEX-only assemble outputs are unchanged; install this new APK explicitly.

The task also relocates merged Java resources into `java-resources.jar`, preserving
the final AGP merge/exclude/pickFirst result and removing the installed copies.
APK signatures and reserved Android build metadata are not Java payload content.
A resource-only parent of the in-memory DEX loader provides class resource URLs,
streams/enumeration and service descriptors before user constructors/providers run.
The JAR uses the same read-only, hash-checked no-backup cache rules as resources.
On pre-existing Binder threads, use an explicit loader for `ServiceLoader`; those
threads do not inherit the app context loader even in normal Android packaging.

Native libraries are likewise relocated into `native-libraries.zip`. Fixed,
shell-owned ABI markers preserve Android's installed ABI selection; they contain
no app logic. Before payload startup, the runtime verifies/extracts the matching
process ABI into a read-only private generation directory and supplies that native
search path to the DEX loader. App libraries are not left installed as a fallback.
See the [native payload gate and limitations](compatibility/jni/PAYLOAD.md).

The production runtime loads this embedded container before constructing the user
Application, component factory or providers. It materializes a content-addressed,
read-only file under no-backup storage, serializes extraction across app processes,
and checks size/hash on every process startup. Application/Activity and derived
configuration resources receive the same loader. With no embedded resource pair,
legacy DEX-only startup stays unchanged. Corrupt/writable caches fail closed; this
slice has no automatic repair UI or external resource-selection API.

Requirements: matching plugin/runtime, minSdk 30+, one unfiltered standalone APK,
ordinary keystore signing configured on the build type, no direct-boot/isolated
components, and at most 256 MiB of embedded resource archive. Existing shrinking
and desugaring restrictions still apply. Custom signing/rotation pipelines are not
supported by this task. Private signing credentials are excluded from task cache
keys; signed output is always regenerated and never build-cached.

This is **embedded code + resources/assets**, not `packaging = 'complete'` or a
signed VPK. All supported app content is relocated in this embedded-only stage;
there is no empty shell, downloaded update, version activation, generation retention
or full shell-contract check. Accepted resource-boundary checks do apply when a
baseline is configured. Cached resource generations currently remain until app
data is cleared; lifecycle/retention work must precede enabling external updates.
See the [production device gate](compatibility/automatic-resources/PRODUCTION.md).

## Verification

For a more involved DI scenario, see the separate [Hilt compatibility probe](compatibility/hilt/README.md).
The optional [paravoid-hilt plugin](paravoid-hilt/README.md) enables Hilt 2.57.2
payload-side lookup rewriting. It passes normal-mode instrumentation tests and
shell-mode cold-launch/recreation checks with the original dependency manifest.
AndroidX Startup obtains the Hilt graph before user Application `onCreate`, and
cold broadcasts exercise AndroidX component wrapping in both modes. Hilt types
stay out of the shell. Annotated services and receivers also pass automatic field
injection checks, including service-scope renewal; broader Hilt compatibility
remains separate work.

Further independent probes exercise larger downstream stacks:

- [Networking and JSON adapters](compatibility/network/README.md): local HTTP/HTTPS,
  reflective/KSP-generated adapters, callbacks, cancellation, cache and TLS negative controls.
- [JNI and native libraries](compatibility/jni/README.md): CMake library dependencies,
  provider startup, JNI callbacks and real process restart in both native storage modes.
- [Views + bindings + fragments](compatibility/views/README.md): custom View state,
  two-way DataBinding, SavedStateHandle, restored back stacks and configuration contexts.
- [Language and discovery](compatibility/language/README.md): Serialization/Parcelize
  plugins, Kotlin/Java reflection, proxies and thread-context service discovery.
- [Compose + Navigation + Hilt](compatibility/compose/README.md): Kotlin/kapt,
  navigation-scoped ViewModels, back handling, rotation, and real process-death
  restoration of `rememberSaveable` and `SavedStateHandle` in both modes.
- [Room + WorkManager](compatibility/storage/README.md): generated DAOs, persistent
  data, and cold background JobService/Worker execution in both modes. This uses
  the default Worker factory, not Hilt Worker injection.
- [Room migrations](compatibility/migrations/README.md): two code versions share
  persistent data; migration, generated DAOs, transaction/index checks and rejected
  downgrade pass on API 36.1. A fixture-only selector switches prebundled payloads
  without replacing the shell APK; this is not a production updater.
- [Named worker processes](compatibility/os/MULTIPROCESS.md): cold remote Service
  entry and same-app Binder calls pass in both modes on API 36.1, including
  independent Application initialization and worker-death recovery. Regular
  `:worker` processes are tested, not isolated services or multiprocess storage.
- [Hilt + WorkManager](compatibility/hilt-work/README.md): generated assisted
  factories and cold injected workers pass with lazy Application initialization
  using optional `paravoid-work`; opt-out retains the original shell failure.
  The [stress extension](compatibility/hilt-work/STRESS.md) verifies a mixed
  Hilt/ordinary-worker retry chain, Data propagation and active cancellation across
  process death in both modes on API 36.1.
- [Independent resources](compatibility/resources/README.md): an API 30+ experiment
  using separate, hash-pinned resource-only APKs and stable IDs. It tests Views,
  Compose, themes, locale lookup and assets while keeping the installed app APK
  unchanged. This is fixture scaffolding, not automatic AGP resource splitting;
  the core plugin still packages resources in the installed APK.
- [Direct Boot](compatibility/direct-boot/README.md): real PIN-locked reboot loads
  the payload Application/receiver and delivers an alarm before unlock. Both modes
  verify DE storage, CE rejection and deferred initialization after unlock on
  API 36.1; third-party library startup remains separate coverage.

```sh
./gradlew :paravoid-gradle-plugin:test :paravoid-gradle-plugin:validatePlugins \
  :paravoid-hilt:test :paravoid-hilt:validatePlugins \
  :paravoid-work:test :paravoid-work:validatePlugins \
  :paravoid-runtime:testDebugUnitTest :sample-app:lintNormalDebug :sample-app:lintParavoidAndroidDebug
```

With an unlocked emulator/device:

```sh
./gradlew :sample-app:connectedNormalDebugAndroidTest \
  :sample-app:connectedParavoidAndroidDebugAndroidTest
```

Use `ANDROID_SERIAL` to select an emulator when multiple devices are connected.
Plugin tests build isolated fixtures, inspect APK class separation and Application
remapping, build AABs, check incremental reuse, and reject unsupported manifest and
Application configurations. Legacy code-only plugin tests and eight bundle-reader
tests remain. Device tests check Application identity and initialization, actual
class loaders, launcher handoff, direct Activity launch, counter interaction, and Activity recreation
in both modes. Activity recreation is not a substitute for process-death testing.

Component expansion verified on 2026-09-19: twenty sample device tests passed
(ten per mode on an API 36.1 emulator), using direct AndroidJUnitRunner invocation.
All twenty-two core plugin tests, ten optional Hilt plugin tests, eight bundle-reader
tests, both plugin validation tasks, and lint for both sample modes passed.
The Hilt probe additionally passed three normal instrumentation tests, two shell
cold-launch/recreation scenarios, and a cold wrapped-receiver check in each mode.
Automatic Hilt injection also passed cold receiver entry and cold service startup
plus service recreation in each mode.
The earlier resource expansion built debug APKs and release AABs in both modes;
lint passed with sample warnings. The bootstrap experiment also checked cold direct Activity launch.
The Compose probe now passes actual process-death restoration; broader restoration
and payload-update compatibility remain future coverage.

## Repository components

| Component | Responsibility |
| --- | --- |
| `paravoid-api` | Legacy code-only entry contract and bundle API version |
| `paravoid-runtime` | Application bases, shell launcher/factory, validation and DEX loading |
| `paravoid-gradle-plugin` | Flavor generation, manifest rewriting, class transformation and packaging |
| `paravoid-hilt` | Optional, version-checked Hilt payload transformation and generated lookup bridge |
| `paravoid-work` | Optional, version-checked WorkManager configuration-owner lookup in the payload |
| `sample-app` | One downstream app demonstrating both packaging modes |
| `sample-library` | Conventional Java dependency with service-provider and resource fixtures |
| `compatibility/compose` | Kotlin Compose, Navigation, Hilt and process-death restoration probe |
| `compatibility/storage` | Room persistence and cold WorkManager execution probe |
| `compatibility/hilt-work` | Hilt workers, lazy/explicit Application configuration and cold injected Room writes |
| `compatibility/resources` | Local resource-pack switching with public API 30+ loaders |
| `compatibility/language` | Compiler plugins, reflection and implicit discovery probe |
| `compatibility/views` | Bindings, custom Views, fragment state and configuration contexts |
| `compatibility/jni` | Native dependencies, library discovery, callbacks and startup |
| `compatibility/network` | HTTP/HTTPS, Retrofit adapters, Moshi KSP, cancellation and caching |
| [`compatibility/os`](compatibility/os/README.md) | FileProvider, notifications, AndroidX results, Binder/AIDL and foreground-Service lifecycle |

The old `sample-shell` and `sample-standalone` source projects are replaced by
generated flavors. The old module/shell plugins and `AppEntry` loader remain as
regression-tested primitives; they are not the new example's integration API.

Each product gets its own shell APK, package identity, signing key, permissions,
and application data. Paravoid Android supplies tooling/runtime rather than a universal
host for unrelated applications.

## Embedded bundle and trust

A single-DEX `module.zip` retains format 1:

```text
module.properties  # format=1, api=1, minSdk, entryPoint (the user Activity)
classes.dex        # application and dependency classes, excluding host runtime
```

Multi-DEX bundles use `format=2` and `dexCount=N`, with contiguous entries
`classes.dex`, `classes2.dex`, …, `classesN.dex`. Limits are 16 DEX files, 32 MiB
per file and 128 MiB total uncompressed DEX, plus 4 KiB metadata. These limits were
raised for the unshrunk Pezzottify integration (about 67.5 MiB across five DEX files).
Larger payloads require the updated runtime; older installed shells still enforce
the old 16 MiB/file and 64 MiB total limits. Loading holds DEX bytes in memory, so
passing these bounds is not a low-memory device guarantee. The reader rejects
missing, duplicate, unexpected or noncontiguous entries and invalid counts/headers.
All DEX buffers load together through one `InMemoryDexClassLoader`. Format 2 needs
API 27+; code-only application packaging requires API 28+ (native-bearing apps need
API 29+). Old shells reject format 2,
so multi-DEX support requires a shell update, not just a replacement payload.

The reader validates metadata and size limits. The shell uses
`InMemoryDexClassLoader` with the host loader as parent, without extracting
executable files. It initializes the transformed Application and makes the loader
available to the component factory. The legacy `BundledModuleLoader` separately
requires `AppEntry`; the new example does not use that loader.

The installed APK signature protects the embedded asset. There is no independent
payload signature or external-file loading API. This must not be treated as
verification of code fetched from an untrusted source. Payload code runs with
the host application's privileges; this is not an isolation boundary.

## Roadmap

The [packaging-first roadmap](ROADMAP.md) defines milestones and acceptance checks:

1. Complete automatic plugin packaging of code, app/library resources, assets,
   Java resources and native libraries; validate with an embedded complete payload.
2. Integrate a real downstream app and turn its failures into focused regressions.
3. Add authenticated delivery, offline availability and safe cold-start activation.
4. Add shell-owned update controls, retained-version management and recovery UI.
5. Later, upload a full package and deliver configuration-targeted resources/ABIs.

The [packaging contract](PACKAGING.md) defines the resource boundary, stable IDs
and API 30+ policy for future complete packaging. Its immediate implementation
gate is proving same-package resource splitting and early loading; existing
DEX-only behavior and minimum SDKs are unchanged. The compatibility suite is regression
coverage, not a reason to defer packaging until every Android scenario is tested.

Downloaded code must use app-private storage with the required read-only protection
and no modification between verification and loading. New manifest capabilities or
incompatible host changes continue to require a shell update.

## References

- [AGP artifact recipes](https://github.com/android/gradle-recipes)
- [Android component factory](https://developer.android.com/reference/android/app/AppComponentFactory)
- [In-memory DEX loading](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)
