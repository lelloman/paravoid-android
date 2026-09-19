# Optional Hilt integration

`paravoid-hilt` is a separate Gradle plugin module. It adapts Hilt component lookup
inside shell payloads; it does not put Hilt interfaces, components, or its lookup
bridge into the shell. Apps that do not apply it are unaffected.

## Enable

For a source checkout, expose both plugin builds in the consuming project's settings:

```groovy
pluginManagement {
    includeBuild('/path/to/paravoid-android/paravoid-gradle-plugin')
    includeBuild('/path/to/paravoid-android/paravoid-hilt')
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
```

Then add the optional plugin alongside the app's normal Hilt setup:

```groovy
plugins {
    id 'com.lelloman.paravoid'
    id 'com.google.dagger.hilt.android' version '2.57.2'
    id 'com.lelloman.paravoid.hilt'
}
dependencies {
    // Keep your existing Paravoid runtime dependency.
    implementation 'com.google.dagger:hilt-android:2.57.2'
    annotationProcessor 'com.google.dagger:hilt-compiler:2.57.2'
}
```

The optional plugin also applies the core Paravoid plugin if necessary. Keep using
`@HiltAndroidApp` on your `ParavoidAndroidApplication` subclass and
`@AndroidEntryPoint` on your Activity. No task configuration, app-owned bridge,
extra Android library, or experimental flag is needed. This repository currently
uses composite builds; these instructions do not assume a published plugin release.

The [compatibility fixture](../compatibility/hilt/README.md) uses the original
dependency manifest, including AndroidX Startup, ProfileInstaller, and
`CoreComponentFactory`. The core runtime loads declared providers, receivers and
services from the payload and preserves provider-before-Application-`onCreate`
ordering. This does not imply support for every Hilt component injection path.

## Boundary and supported versions

- Only `paravoidAndroid` variants register the adapter. Normal APKs use unmodified
  Hilt and contain no generated Paravoid Hilt bridge.
- The plugin checks the **selected** runtime versions of `hilt-android` and
  `hilt-core`, including transitive dependency resolution. Both must be `2.57.2`.
  Use the matching Hilt Gradle plugin/compiler as tested above; other combinations
  are not validated. The separate [Compose probe](../compatibility/compose/README.md)
  verifies Kotlin 2.2.21 with kapt; KSP remains unvalidated.
- Rewriting runs after Hilt instrumentation and before payload base remapping/D8,
  via the core's generic `PayloadTransformer` hook. Transformer configuration is
  tracked as nested Gradle task input, including the resolved runtime versions.
- Exact target methods and expected edit counts are checked. Missing classes,
  unsupported versions, and bridge-name collisions fail the build.
- `com.lelloman.paravoidandroid.hilt.internal.HiltLookup` is generated directly
  into the payload and resolves its attached initializer through a Hilt-free
  shell API. Ordinary `getApplication()` calls still return the shell Application.
- The integration does not rewrite every Hilt/Android lookup. Activity injection,
  explicit application entry points, Application/context bindings, and Hilt
  ViewModel recreation are tested. An AndroidX Startup initializer and a cold
  broadcast receiver also resolve the same graph through explicit entry points.
  Automatic `@AndroidEntryPoint` service/receiver injection is adapted through
  their Hilt component managers. Cold-start tests check field injection, singleton
  identity and service-scope renewal after stopping/recreating the service.
  Other entry points, WorkManager, custom
  Application casts and shrinking remain unvalidated. The Compose probe verifies
  navigation-scoped Hilt ViewModels and saved-state restoration after process death;
  that is not a general guarantee for arbitrary state or payload updates.

## Verify

From the repository root:

```sh
./gradlew -p paravoid-hilt test validatePlugins
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5556 \
  bash compatibility/hilt/check.sh --device
```

Unit/functional tests cover rewriting, version selection, packaging boundaries,
incremental inputs, and opt-out behavior. Device checks use the real payload graph.
See the fixture report for the existing shell instrumentation limitation and the
cold-process checks used instead.
