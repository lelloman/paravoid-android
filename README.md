# VoidAndroid

VoidAndroid explores a native Android application with two release cycles: an
installed shell that changes infrequently, and signed application modules that
the shell downloads and runs without reinstalling its APK.

The same application implementation should also build as a traditional,
self-contained Android APK or AAB. Both modes are intended to render native UI,
including Jetpack Compose.

## Agreed downstream architecture

The downstream developer keeps an ordinary Android application project. The
intended integration contract is:

- Apply the VoidAndroid Gradle plugin and configure the update server address,
  authentication if needed, and packaging settings.
- Use one user Activity with a stable class name. It can be an ordinary Activity
  or ComponentActivity with its normal Compose `setContent` setup; no VoidAndroid
  Activity superclass, annotation, or special Compose entry function is required.
- When custom Application behavior is needed, extend `VoidAndroidApplication`.
  The plugin discovers this class from the merged manifest; no Application
  annotation or separately configured initializer is required.
- Select normal or VoidAndroid packaging through a flavor dimension generated
  by the plugin, alongside the app's build types and existing flavor dimensions.

The plugin packages the entire application implementation, including its
dependencies and eventually its resources and assets. Downstream developers
should not manually split their project into implementation, shell, and standalone
modules, implement `AppEntry`, or write bootstrap code.

| Packaging mode | Intended outputs and behavior |
| --- | --- |
| Normal packaging | Conventional APK/AAB containing the app; the user's Application subclass is the actual Android Application |
| VoidAndroid packaging | Generated shell APK/AAB plus a separately packaged application payload; the shell owns the actual Android Application and bootstrap launcher |

The dimension, flavor names, plugin ID, and configuration DSL for this public
integration are still to be finalized. These are agreed design goals, not APIs
implemented by the current plugin.

### Application behavior

The intended downstream source looks like this (illustrative, not yet available):

```kotlin
class MyApplication : VoidAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        // Application initialization
    }
}
```

In normal packaging, this class is the app's Android Application. In VoidAndroid
packaging, the plugin transforms the user-defined behavior into a separate
payload class that is not an Android Application. The shell's Application calls
that behavior once the selected payload is ready, before creating the user
Activity. There is only one actual Android Application per process.

The familiar downstream API is intentional: packaging differences and the
transformation burden belong to VoidAndroid. A separate `VoidAppInitializer`
interface is not the chosen public API.

This transformation has not been implemented or proven. Its contract must cover
fields, `super` calls, context access, callbacks, and initialization once per
process, including process restoration. Custom Application casts, object identity,
dependency-injection integrations, and libraries that initialize through providers
need explicit compatibility decisions and tests; arbitrary Application behavior
is not yet guaranteed to work.

### Activity startup and installed manifest

The generated shell declares two Activities: VoidAndroid's `LauncherActivity`
and the user's Activity. The plugin assigns the launcher intent filter to the
bootstrap Activity and preserves the user Activity's component identity and
required manifest configuration. The bootstrap prepares and verifies the payload,
completes user application initialization, starts the user Activity through Android,
and finishes itself. Android manages the real user Activity's lifecycle; Activity
method extraction and lifecycle forwarding are not part of this design.

The one-user-Activity rule applies to the merged manifest, including Activities
contributed by dependencies. Unsupported additional Activities should produce a
clear build error. Screens and navigation live within the one user Activity.
Its class name and installed manifest configuration remain stable across payload
updates; changing that contract requires a shell update.

Loading a DEX class is not sufficient to make Android instantiate an Activity.
The runtime must integrate payload class loading with Android component creation;
`AppComponentFactory` is a candidate to validate. Loading must also work when
Android restores the user Activity after process death or launches it directly,
without first visiting the bootstrap launcher. Minimum Android version support
for this architecture remains to be established.

The shell retains the installed manifest, permissions, bootstrap code, and
resources required by that manifest and bootstrap UI. Payload updates cannot
dynamically add installed component declarations or permissions. Resource loading,
dependency packaging, services, receivers, providers, and other startup paths
still need implementation and compatibility rules. The integration contract above
does not imply support for every existing Android app or library.

## Status

The repository currently implements a lower-level loading experiment, not the
agreed downstream architecture above. There is no generated packaging flavor
dimension, Application transformation, or dynamically launched user Activity yet.

The first experiment builds a code-only DEX module, embeds it inside a shell APK,
and loads it at runtime to display a native Android screen. It needs no server,
network permission, or runtime download. A second APK runs the same application
implementation through a traditional dependency.

The build tooling is a Gradle plugin from the start. The sample currently uses
Java and Android Views to isolate the loading experiment; Compose integration is
future work. This project is independent of LelloStore.

## Build and run

Requirements: JDK 17 or newer (tested with 21), Android SDK platform 36 and Build
Tools 35.0.0, and Android 8.0 / API 26 or newer to run the samples. The wrapper
pins Gradle 8.13 and the plugin pins Android Gradle Plugin 8.13.2. Gradle may need
network access to fetch build dependencies; the installed apps work offline.

Set `ANDROID_HOME` or create an ignored `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

```sh
./gradlew :sample-shell:assembleDebug :sample-standalone:assembleDebug
adb install -r sample-shell/build/outputs/apk/debug/sample-shell-debug.apk
adb install -r sample-standalone/build/outputs/apk/debug/sample-standalone-debug.apk
```

Open **VoidAndroid Shell** and **VoidAndroid Standalone** from the launcher. Both
show the same persistent counter. The shell reports `InMemoryDexClassLoader`;
the standalone uses the normal application class loader. They have separate
application IDs and separate saved counters.

The plugin also exposes `:sample-app:bundleDebugVoidModule`, producing
`sample-app/build/outputs/void/debug/module.zip`. The shell contains this file at
`assets/void/module.zip`; the application implementation is absent from the
shell's ordinary DEX classpath.

## Current prototype plugin usage

These instructions describe the existing experiment. Its manually separated
projects and `AppEntry` interface are not the intended downstream integration API.

The repository includes `void-gradle-plugin` as a composite build through
`pluginManagement.includeBuild`. No plugin publication is needed for development.

In the application implementation project:

```groovy
plugins { id 'com.lelloman.void.module' }
android {
    namespace 'example.product'
    compileSdk 36
    defaultConfig { minSdk 26 }
}
voidModule { entryPoint = 'example.product.ProductEntry' }
dependencies { compileOnly project(':void-api') }
```

The module plugin applies `com.android.library`. For each variant it consumes
AGP's AAR artifact, extracts `classes.jar`, compiles it with the SDK's D8, and
publishes a bundle through a dedicated outgoing Gradle configuration. Its entry
point must implement `AppEntry` and have a public no-argument constructor.

In the shell project:

```groovy
plugins { id 'com.lelloman.void.shell' }
// Configure android namespace, applicationId, SDK versions, etc. as usual.
dependencies {
    implementation project(':void-runtime')
    voidBundle project(':sample-app')
}
```

The shell plugin applies `com.android.application` and embeds exactly one bundle
using AGP's generated assets API. Producer and consumer variant names must match
(e.g. debug to debug, release to release). `voidBundle` is separate from
`implementation`, so module classes are not merged into the shell's normal DEX.
The shell Activity calls `BundledModuleLoader.load(this)` and displays the view
returned by `AppEntry.createView`.

A standalone host uses the ordinary `com.android.application` plugin with
`implementation project(':sample-app')` and `implementation project(':void-api')`.
It creates the same entry class directly. See `sample-standalone` for the complete
example. Host Activities remain explicit in this prototype; the plugin does not
generate them or their manifest declarations.

## Verification

```sh
./gradlew :void-gradle-plugin:test :void-gradle-plugin:validatePlugins \
  :void-runtime:testDebugUnitTest :sample-shell:lintDebug :sample-standalone:lintDebug
```

With an unlocked emulator or device connected:

```sh
./gradlew :sample-shell:connectedDebugAndroidTest
```

Plugin functional tests build a separate fixture, inspect the APK to prove module
classes live only in the embedded bundle, check incremental task reuse, and
exercise unsupported-content failures. Runtime unit tests reject incompatible or
malformed bundles. Device tests verify actual dynamic class loading, native UI
interaction, and persistent state across Activity recreation.

Initial verification: debug and release builds, plugin validation, four plugin
functional tests, five bundle-reader unit tests, and two device tests passed.
Device tests and a standalone launch smoke test ran on an Android API 36.1
emulator. Gradle configuration-cache storage and reuse also passed. Lint reports
no errors; sample warnings remain for target/dependency versions, application
icons/backup configuration, and intentionally hardcoded demo text.

## Distribution model

Each product gets its own shell APK, package identity, signing key, permissions,
and application data. VoidAndroid supplies the reusable shell/runtime and build
tooling, rather than requiring a universal application hosting unrelated apps.

```text
                         Shared application implementation
                                      |
                         +------------+------------+
                         |                         |
                   Normal APK/AAB           Signed app payload
                   includes app code               |
                                            Installed shell APK
                                            verifies and loads code
```

Shell and standalone variants can use different application IDs to coexist
during development. Those variants have separate Android application data.

## Current repository components

| Component | Responsibility |
| --- | --- |
| `void-api` | Versioned entry point and host services contract |
| `void-runtime` | Embedded bundle validation and in-memory DEX loading |
| `void-gradle-plugin` | DEX module packaging and embedding into a shell APK |
| `sample-app` | Demonstrate both modes with the same native application implementation |
| `sample-shell` | Minimal installed shell hosting the bundled module |
| `sample-standalone` | Traditional APK using the implementation directly |

These projects are prototype implementation details, not a required downstream
project layout. The intended plugin will generate the shell packaging from the
downstream app. Downloading and external updates are not implemented.

## Current prototype host and module boundary

The shell owns Android manifest declarations, system components, permissions,
the module loader, and the host contract. The module owns application screens,
navigation, business logic, and application-specific data access.

An initial entry point can return an Android `View`, including a `ComposeView`.
The standalone launcher constructs the implementation directly; the shell loads
it from verified DEX code and invokes the same contract.

The first experiment shares only the host API and Android framework. Additional
libraries must be provided by both hosts and referenced with `compileOnly` in
the module. The module task rejects runtime dependencies, resources, assets,
native libraries, embedded JARs, and multidex output rather than dropping them.
Only the module's classes are loaded; its manifest cannot add system components.
Shrinking/obfuscation and core library desugaring are not supported in this
prototype. These restrictions do not define the intended whole-app payload:
dependency packaging and compatibility still need to be designed and tested.

Code loading does not automatically integrate Android resources from a module.
Begin with minimal resources and explicit asset loading; prove a resource
strategy before supporting general application packaging. Dependency injection,
Application transformation, component creation, process recreation, and background
work also need explicit integration rather than assuming an arbitrary APK can run
unchanged.

## Current embedded bundle format and trust

`module.zip` contains exactly two entries:

```text
module.properties  # format=1, api=1, minSdk, entryPoint
classes.dex        # module implementation; no copy of the host API
```

The loader validates metadata and size limits, then uses
`InMemoryDexClassLoader` with the host API's loader as its parent. No executable
file is extracted to writable storage. It also checks that the entry point was
actually loaded by the module loader and implements the shared contract.

The installed APK signature protects the embedded asset. This experiment has no
independent module signature, downloader, or external-file loading API. It must
not be treated as verification of code fetched from an untrusted location.
Changing the bundled module currently requires rebuilding/reinstalling the shell.

## Future external updates

- Sign bundles with the same private key used to sign the product's shell.
- Read the installed shell's signing certificate and verify the downloaded
  bundle against its public key before loading code. Matching an embedded
  certificate alone is not signature verification.
- Authenticate code, assets, and metadata together. Private keys stay in release
  infrastructure and never ship with the application.
- Define a bundle format and signature algorithm before implementing signing.
  Account for certificate rotation explicitly rather than implicitly trusting
  every historical signer.
- Download into app-private storage, follow Android's read-only requirements for
  dynamically loaded files, and prevent modification between verification and
  loading.
- Activate staged updates on a cold launch. Do not replace running classes.
- Keep a last-known-good bundle, define a startup health signal, and test bounded
  recovery from a failing module. Define version/replay policy alongside rollback.
- Keep compatible modules cached for offline use. Specify first-launch behavior
  when no module is available.
- Treat database migrations as part of update compatibility: rolling back code
  alone does not undo a schema change.

Modules run with the host application's privileges; this is a trusted-code
architecture, not an isolation boundary for untrusted publishers. A shell update
remains necessary for new manifest capabilities or incompatible host changes.

## Next experiments

1. Prove the agreed integration using one ordinary downstream app project and
   a plugin-generated packaging flavor dimension. Build normal APK/AAB and shell
   outputs from the same source without a downstream module split or `AppEntry`.
2. Generate the two-Activity shell manifest and launch the real user Activity
   from an embedded payload. Verify lifecycle behavior, direct launch, and process
   restoration; establish the supported Android versions.
3. Implement and verify `VoidAndroidApplication` transformation: actual Application
   in normal packaging, payload behavior called by the shell Application in Void
   packaging. Test initialization order, state, `super` calls, and context access.
4. Demonstrate the same Compose screen in both modes, including resource and
   dependency loading. Define supported library and component integrations.
5. Define and verify independently signed bundles, including negative signature
   tests, before accepting modules from outside the installed APK.
6. Add server/authentication configuration, staged external updates, compatibility
   policy, offline fallback, startup health checks, and rollback tests. Define
   first-launch payload availability and initialization behavior explicitly.

The completed prototype milestone is plugin-driven packaging and native View
execution from an embedded code-only module. The next milestone is proving the
agreed downstream API and generated shell with an embedded whole-app payload;
network delivery can follow after that integration works.

## References

- [AGP recipes: generated source directories and artifacts](https://github.com/android/gradle-recipes)
- [Android in-memory DEX loading](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)
