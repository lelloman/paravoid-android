# Hilt compatibility probe

Status: **the optional [paravoid-hilt module](../../paravoid-hilt/README.md) passes
the scoped checks below on API 36.1.** This is an independent build, not part of the
default resource sample. It pins Hilt 2.57.2, AndroidX Activity 1.10.1, AGP 8.13.2
and Gradle 8.13, using Java annotation processing and the standard Hilt plugin.

The downstream shape is unchanged: `ProbeApplication` extends
`ParavoidAndroidApplication` with `@HiltAndroidApp`; `ProbeActivity` extends
`ComponentActivity` with `@AndroidEntryPoint`.

## Measured results

- Three normal-mode instrumentation tests pass using the production Application.
- Two shell-mode cold-process checks pass: launcher handoff and direct Activity
  entry. Both then recreate the Activity and verify the retained graph/state.
- Cold receiver entry passes in both modes. A `CompatWrapped` receiver requires
  AndroidX `CoreComponentFactory` delegation, then resolves the same Hilt singleton.
- Separate `@AndroidEntryPoint` receiver and service probes cold-start in both
  modes and verify automatic field injection and shared singleton identity. The
  service stops and starts again in the same process: its `@ServiceScoped` token
  is shared within each instance but replaced between instances. Injected Android
  Application/Context references keep their real identity.
- An AndroidX Startup initializer resolves the graph before user Application
  `onCreate`; Application injection subsequently reuses that singleton. Startup,
  ProfileInstaller and the AndroidX factory remain in the merged manifest.
- Application/Activity injection and explicit application entry points use the
  same singleton. The Hilt ViewModel shares it and retains its SavedStateHandle
  value across recreation; Activity scope is renewed; Application initialization
  runs once per process.
- Injected `Application`, `@ApplicationContext`, and ordinary Activity Application
  access all refer to the real shell Application, not the payload initializer.
- Hilt's interface and Activity component manager, plus the user Application and
  Activity, come from the in-memory payload loader. The shell cannot load Hilt's
  component-manager interface and does not implement it.

These are real graph tests, not `HiltTestApplication` or a replacement injector.
They do not prove saved-state restoration after process death. That scenario is
covered separately by the [Compose/Navigation probe](../compose/README.md).

## Reproduce

Use JDK 17+, the repository's Android SDK, and `rg`/`adb`. The independent build
needs `ANDROID_HOME` or its own ignored `local.properties`.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
bash compatibility/hilt/check.sh
ANDROID_SERIAL=emulator-5556 bash compatibility/hilt/check.sh --device
```

The build-only check verifies normal APKs and the adapted shell APK with the
unmodified dependency manifest. `--device` additionally runs normal instrumentation
directly through `adb shell am instrument`, `shell-device-check.sh`, and
`injected-device-check.sh`. The scripts install the probes, force-stop them between
scenarios, and check unique per-run results written only after the in-process
assertions pass. The service test temporarily allowlists the app for background
starts; it does not test foreground-service or background-execution policy. Use a
dedicated device.

The fixture applies `com.lelloman.paravoid.hilt`. To build its shell directly:

```sh
./gradlew -p compatibility/hilt assembleParavoidAndroidDebug
```

Logs are under the fixture's `build/compatibility/shell-build.log` and, with
`--device`, `normal-device.log`.
With the fixture-only `-PhiltProbeDisableIntegration=true`, the optional plugin is
not applied. The shell still builds but has no Hilt lookup adapter; packaging
tests verify the bridge is absent. The earlier Activity-only fixture failed at
Activity injection without it. The current fixture also accesses Hilt earlier,
during provider startup, so opt-out is not a runnable Hilt configuration.

## What the optional integration changes

The core contains no Hilt adapter. Applying the optional plugin registers one for
shell variants only. It runs after Hilt's ASM transform
and before our DEX generation, targeting five Hilt 2.57.2 implementation classes:

| Target | Adaptation |
| --- | --- |
| `ActivityComponentManager.createComponent()` | Resolve the payload component owner instead of checking the shell Application |
| `ServiceComponentManager.createComponent()` | Resolve that owner to build the service-scoped component, preserving the actual Service binding |
| `BroadcastReceiverComponentManager.generatedComponent(Context)` | Resolve that owner for automatic receiver injection |
| `EntryPointAccessors.fromApplication(Context, Class)` | Resolve that same owner for retained components and explicit entry points |
| `ApplicationContextModule` constructor | Normalize its Context binding to the real Android application context |

The module generates a payload-only `HiltLookup` bridge, which accesses the
attached payload initializer through a Hilt-free runtime API. Ordinary user `getApplication()` calls and Hilt's
`Application` provider are not redirected. Unit tests check targeting, unchanged
ordinary calls, bridge-name collisions, and unexpected edit counts. The plugin
also rejects missing or unsupported resolved Hilt runtime versions. Functional
tests check normal/shell boundaries and removal of the optional plugin.

Before extending the adapter, the new annotated receiver and service passed in
normal mode but both crashed in shell mode at Hilt's Application ownership check.
The targeted rewrites fix those paths without changing downstream annotations or
ordinary Android Application access.

Two general packaging fixes were needed: accepting an indirect Application base,
and giving the payload Activity a `getClassLoader()` override when its payload
hierarchy does not already declare one. The latter fixes Android's restoration
of AndroidX's platform `ReportFragment`. Explicit downstream overrides are preserved.
Java module descriptors are also filtered; real duplicate classes still fail.

The runtime now prepares the loader and constructs the transformed Application
during shell attachment, before Android installs providers. It forwards the user
`onCreate` only from the actual Application `onCreate`. Component creation delegates
to the preserved AndroidX factory using the payload loader. These are core runtime
features, not Hilt-specific hooks.

## Limits

- The plugin now accepts additional non-launcher app/library Activities under the
  [fixed installed contract](../../PACKAGING.md#activity-declarations-and-library-components),
  but this Hilt fixture only
  verifies a single Activity. Declared providers, services and
  receivers are accepted; arbitrary factories other than the platform default or
  AndroidX `CoreComponentFactory` are rejected. Multiprocess, isolated-process and
  direct-boot components are not validated.
- Shell-mode AndroidJUnitRunner currently crashes before Application startup:
  AGP omits shared Kotlin dependencies from the test APK, but they are only in the
  payload, unavailable to the instrumentation parent loader. The shell checks
  deliberately run without instrumentation rather than copying Hilt/Kotlin into
  the shell and hiding the production classloader boundary.
- Other Hilt versions, KSP, Fragment/View injection, Hilt Worker injection, shrinking,
  custom Application casts and bound/foreground service scenarios are not validated.
  The Compose probe separately covers Kotlin/kapt and navigation state after
  process death. The [storage probe](../storage/README.md) tests Room and ordinary
  WorkManager workers, not Hilt Workers. The SDK D8 also emits Kotlin metadata-version
  warnings for this dependency graph; these successful debug tests do not settle
  broader Kotlin compatibility.
- The previous prototype task flag, fixture-owned bridge, and diagnostic manifest
  overlay have been removed. Integration is owned by `paravoid-hilt`.

References: [Hilt Gradle transformation](https://dagger.dev/hilt/gradle-setup.html),
[Hilt applications](https://dagger.dev/hilt/application.html),
[Hilt ViewModels](https://dagger.dev/hilt/view-model.html).
