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
- Use one user Activity with a stable class name. It can be an ordinary Activity
  or ComponentActivity with its normal Compose `setContent` setup; no Paravoid Android
  Activity superclass, annotation, or special Compose entry function is required.
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

The generated shell declares two Activities: Paravoid Android's `LauncherActivity`
and the user's Activity. The plugin assigns the launcher intent filter to the
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

The one-user-Activity rule applies to the merged manifest, including Activities
contributed by dependencies. Unsupported additional Activities should produce a
clear build error. Screens and navigation live within the one user Activity.
Its class name and installed manifest configuration remain stable across payload
updates; changing that contract requires a shell update.

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

The shell retains the installed manifest, permissions, bootstrap code, and
resources required by that manifest and bootstrap UI. Payload updates cannot
dynamically add installed component declarations or permissions. Declared services,
receivers and providers are preserved and instantiated through the payload loader.
The factory delegates to AndroidX `CoreComponentFactory` when present, including
its component wrapping. Arbitrary custom factories remain unsupported because
their Application/classloader hooks need a separate contract. Multiprocess,
isolated-process and direct-boot components are not validated. The integration contract above
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
The [network probe](compatibility/network/README.md) covers Retrofit/OkHttp,
Gson/Moshi adapters, KSP, cancellation, caching and local HTTPS verification.
Its pinned Moshi/KSP2 generated-qualifier limitation is explicitly documented;
ordinary generated models and reflective qualifiers are tested separately.

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
  Older devices receive an explicit initialization error; there is not yet a
  build-time minimum-SDK check for native dependencies. Code-only apps retain API 28+.
- The main sample uses Java and Android Views; a separate Compose/Hilt/Navigation
  fixture verifies Kotlin UI and process-death state restoration.
- Extra Activities, Activity aliases, and component factories other than the
  platform default or AndroidX `CoreComponentFactory` are rejected in Paravoid mode.
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
dependencies { implementation project(':paravoid-runtime') }
```

The plugin applies `com.android.application` and generates the packaging flavors.
Declare the Application and Activity in the ordinary manifest; no entry-point
configuration is needed. With no custom Application, user initialization is skipped.
The sample adds application ID suffixes for convenience; these are not required.

For Paravoid variants, the plugin consumes AGP's scoped classes, transforms the
Application base with ASM, compiles payload classes through D8, and embeds the
bundle through generated assets. Its manifest transformation installs the shell
Application, launcher, and component factory. Normal variants retain ordinary
Android packaging.

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
- [Independent resources](compatibility/resources/README.md): an API 30+ experiment
  using separate, hash-pinned resource-only APKs and stable IDs. It tests Views,
  Compose, themes, locale lookup and assets while keeping the installed app APK
  unchanged. This is fixture scaffolding, not automatic AGP resource splitting;
  the core plugin still packages resources in the installed APK.

```sh
./gradlew :paravoid-gradle-plugin:test :paravoid-gradle-plugin:validatePlugins \
  :paravoid-hilt:test :paravoid-hilt:validatePlugins \
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
| `sample-app` | One downstream app demonstrating both packaging modes |
| `sample-library` | Conventional Java dependency with service-provider and resource fixtures |
| `compatibility/compose` | Kotlin Compose, Navigation, Hilt and process-death restoration probe |
| `compatibility/storage` | Room persistence and cold WorkManager execution probe |
| `compatibility/resources` | Local resource-pack switching with public API 30+ loaders |
| `compatibility/language` | Compiler plugins, reflection and implicit discovery probe |
| `compatibility/views` | Bindings, custom Views, fragment state and configuration contexts |
| `compatibility/jni` | Native dependencies, library discovery, callbacks and startup |
| `compatibility/network` | HTTP/HTTPS, Retrofit adapters, Moshi KSP, cancellation and caching |
| [`compatibility/os`](compatibility/os/README.md) | Cross-UID FileProvider, cold notification PendingIntents and AndroidX result restoration |

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
`classes.dex`, `classes2.dex`, …, `classesN.dex`. Limits are 16 DEX files, 16 MiB
per file and 64 MiB total uncompressed DEX, plus 4 KiB metadata. The reader rejects
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

## Next experiments

1. Expand transformation compatibility and test cold process restoration, callback
   behavior, and supported Android versions beyond the current sample.
2. Turn the independent-resource experiment into automatic app/library resource
   packaging with stable-ID and installed-manifest contracts. Test broader library
   cases: Hilt Workers, Room migrations, OS integration and third-party native SDKs.
3. Define independently signed payloads and negative signature tests before accepting
   code from outside the APK. Sign with the product shell's signing key and verify
   against the installed shell certificate's public key; matching certificates
   alone is not signature verification. Define certificate rotation explicitly.
4. Add server/authentication configuration and staged external updates. Authenticate
   code, assets, and metadata together; keep private keys in release infrastructure.
5. Define first-launch payload availability, offline caching, version/replay policy,
   startup health signals, last-known-good fallback, and bounded rollback. Activate
   updates on cold launches rather than replacing running classes. Account for
   database migrations: reverting code does not undo a schema change.

Downloaded code must use app-private storage with the required read-only protection
and no modification between verification and loading. New manifest capabilities or
incompatible host changes continue to require a shell update.

## References

- [AGP artifact recipes](https://github.com/android/gradle-recipes)
- [Android component factory](https://developer.android.com/reference/android/app/AppComponentFactory)
- [In-memory DEX loading](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)
