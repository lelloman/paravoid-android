# Hilt compatibility probe

Status: **the payload-side rewrite experiment passes on API 36.1; general Hilt
support is not available yet.** This is an independent build, not part of the
default resource sample. It pins Hilt 2.57.2, AndroidX Activity 1.10.1, AGP 8.13.2
and Gradle 8.13, using Java annotation processing and the standard Hilt plugin.

The downstream shape is unchanged: `ProbeApplication` extends
`ParavoidAndroidApplication` with `@HiltAndroidApp`; `ProbeActivity` extends
`ComponentActivity` with `@AndroidEntryPoint`.

## Measured results

- Three normal-mode instrumentation tests pass using the production Application.
- Two shell-mode cold-process checks pass: launcher handoff and direct Activity
  entry. Both then recreate the Activity and verify the retained graph/state.
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
They do not prove saved-state restoration after process death.

## Reproduce

Use JDK 17+, the repository's Android SDK, and `rg`/`adb`. The independent build
needs `ANDROID_HOME` or its own ignored `local.properties`.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
bash compatibility/hilt/check.sh
ANDROID_SERIAL=emulator-5556 bash compatibility/hilt/check.sh --device
```

The build-only check verifies normal APKs, the remaining default-manifest
rejection, and the adapted shell APK. `--device` additionally runs normal
instrumentation and `shell-device-check.sh`. The latter installs the shell probe,
force-stops it between scenarios, and checks a unique per-run result written only
after its in-process assertions and recreation pass. Use a dedicated device.

To build the experimental shell directly:

```sh
./gradlew -p compatibility/hilt assembleParavoidAndroidDebug \
  -PhiltProbeMinimalManifest=true -PhiltProbeAdapter=true
```

Logs are under `build/compatibility/manifest.log` and `adapted-build.log`.
Without `hiltProbeAdapter=true`, the diagnostic shell still builds but Activity
injection fails. That negative baseline was reproduced on the emulator:
`Hilt Activity must be attached to an @HiltAndroidApp Application. Found: ...ShellApplication`.

## What the experiment changes

The adapter is off by default. For this fixture it runs after Hilt's ASM transform
and before our DEX generation, targeting three Hilt 2.57.2 implementation classes:

| Target | Adaptation |
| --- | --- |
| `ActivityComponentManager.createComponent()` | Resolve the payload component owner instead of checking the shell Application |
| `EntryPointAccessors.fromApplication(Context, Class)` | Resolve that same owner for retained components and explicit entry points |
| `ApplicationContextModule` constructor | Normalize its Context binding to the real Android application context |

The payload-only `HiltLookup` bridge accesses the attached payload initializer
through a Hilt-free runtime API. Ordinary user `getApplication()` calls and Hilt's
`Application` provider are not redirected. Unit tests check targeting, unchanged
ordinary calls, missing bridges, and unexpected edit counts. These guards detect
known structural changes; they are not a compatibility guarantee for other Hilt
versions.

Two general packaging fixes were needed: accepting an indirect Application base,
and giving the payload Activity a `getClassLoader()` override when its payload
hierarchy does not already declare one. The latter fixes Android's restoration
of AndroidX's platform `ReportFragment`. Explicit downstream overrides are preserved.
Java module descriptors are also filtered; real duplicate classes still fail.

## Limits and next step

- Default AndroidX manifests still hit the unsupported receiver/provider/factory
  checks. The diagnostic overlay removes those entries only to isolate this test.
  One user Activity is the intended rule; other component support is unfinished.
- Shell-mode AndroidJUnitRunner currently crashes before Application startup:
  AGP omits shared Kotlin dependencies from the test APK, but they are only in the
  payload, unavailable to the instrumentation parent loader. The shell checks
  deliberately run without instrumentation rather than copying Hilt/Kotlin into
  the shell and hiding the production classloader boundary.
- Other Hilt versions, Kotlin/KSP, Fragment/View injection, services, receivers,
  providers, WorkManager, shrinking, custom Application casts, and process-death
  restoration are not validated. The SDK D8 also emits Kotlin metadata-version
  warnings for this dependency graph; these successful debug tests do not settle
  broader Kotlin compatibility.
- The prototype currently lives behind an experimental packaging-task property
  and references this fixture's bridge. It is not a public integration API.
  Next: extract the transformer and payload bridge into an optional `paravoid-hilt`
  integration, with explicit version support and no Hilt dependency in the shell.

References: [Hilt Gradle transformation](https://dagger.dev/hilt/gradle-setup.html),
[Hilt applications](https://dagger.dev/hilt/application.html),
[Hilt ViewModels](https://dagger.dev/hilt/view-model.html).
