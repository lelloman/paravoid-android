# Optional WorkManager integration

`com.lelloman.paravoid.work` adapts **WorkManager 2.10.1** lazy
`Configuration.Provider` discovery for Paravoid shell packaging. It does not
depend on Hilt and adds no WorkManager types to the installed shell DEX.

```groovy
// settings.gradle, while consuming this repository locally:
pluginManagement {
    includeBuild('/path/to/paravoid-android/paravoid-gradle-plugin')
    includeBuild('/path/to/paravoid-android/paravoid-work')
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
```

```groovy
// Application build.gradle:
plugins {
    id 'com.lelloman.paravoid'
    id 'com.lelloman.paravoid.work'
}
dependencies { implementation 'androidx.work:work-runtime:2.10.1' }
```

Keep the standard custom-configuration setup: your manifest Application extends
`ParavoidAndroidApplication`, implements `Configuration.Provider`, and returns
the desired WorkerFactory configuration. Remove WorkManager's default Startup
initializer metadata from the merged manifest (not unrelated initializers).
No manual `WorkManager.initialize()` call or app-owned lookup bridge is required.
For Hilt workers, apply the separate `com.lelloman.paravoid.hilt` integration too,
with the supported Hilt plugin/compiler/runtime and injected HiltWorkerFactory.

## Boundary

Only `paravoidAndroid` payload classes are rewritten. The adapter inserts an owner
lookup immediately before the provider `instanceof` and cast in the exact pinned
`WorkManagerImpl.getInstance(Context)` method. It resolves the attached payload
Application for those two operations. The local Application context passed into
WorkManager initialization is unchanged; ordinary app `getApplicationContext()`
and `getApplication()` calls still return the real shell Application.

Selected runtime version, missing classes, bridge collisions and unexpected
provider-check/cast counts fail the build. The resolved version is a tracked
transformer input. Normal packaging is untouched. The generated `WorkLookup`
class lives only in the payload; disabling the plugin removes that adaptation.

This is not arbitrary Application interface forwarding, a generic WorkManager
version adapter, or a multiprocess integration. Hilt field injection still must
finish before configuration is requested. Provider-time access before Application
`onCreate`, retries/chains, foreground/periodic work, reboot, Kotlin worker
generation, payload-version migration and other WorkManager versions are not
established by the current probes.

## Verification

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :paravoid-work:test :paravoid-work:validatePlugins
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/check.sh lazy --device
```

The [Hilt Work probe](../compatibility/hilt-work/README.md) now passes lazy
configuration in both modes on API 36.1/debug/x86_64: cold injected worker without
an Activity, fresh graph, and Room persistence across three processes.
The [storage probe](../compatibility/storage/README.md) includes an independent
non-Hilt custom-configuration control.
