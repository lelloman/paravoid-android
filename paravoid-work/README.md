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
# Both Hilt and non-Hilt custom configuration:
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/work-check.sh
```

The [Hilt Work probe](../compatibility/hilt-work/README.md) now passes lazy
configuration in both modes on API 28, 29 and 36.1/debug/x86_64: cold injected worker without
an Activity, fresh graph, and Room persistence across three processes.
The [storage probe](../compatibility/storage/README.md) includes an independent
non-Hilt custom-configuration control.
That custom-configuration control also passes both modes on API 28, 29 and 36.1.
The API 28/29 expansion adds eight complete three-process scenarios (two fixtures
× two modes × two APIs), without production changes. Storage now shares the
guarded process-death helper: API 29 Google APIs rev13 requires its existing
debug-emulator `su` fallback after a denied `run-as` signal. The five helper guard
tests pass. No SELinux/adbd changes or target-side instrumentation were used.

Verified on the same API 36.1 emulator in normal/shell modes:

- Hilt lazy configuration and cold assisted worker injection.
- Non-Hilt custom configuration/factory, cold callback PIDs and real Application context.
- Default WorkManager Startup initialization with this adapter enabled and no custom Application.
- Plugin opt-out restores the original lazy shell failure while normal still passes.

The adapted Hilt lazy matrix passed twice, including after rebuilding from the
opt-out reproducer. Both-mode lint passes for Hilt, non-Hilt configured and default
initialization fixtures.

69 host tests pass across core, runtime, Hilt and Work integrations, along with all
plugin validators. Nine Work tests cover exact rewrite scope, generated bridge
behavior, selected version rejection, payload-only packaging, incremental reuse
and opt-out. Default-initializer-with-adapter and opt-out device evidence remains
API 36.1 only. Device results are debug/x86_64; release, physical devices and other
ABIs have not been verified for this integration.
