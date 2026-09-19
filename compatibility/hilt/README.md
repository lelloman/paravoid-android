# Hilt compatibility probe

Status: **normal packaging works; Paravoid packaging is not supported yet.**
This is an independent build, not part of the default resource sample. It pins
Hilt 2.57.2 and AndroidX Activity 1.10.1 with the repository's AGP 8.13.2 and
Gradle 8.13. It uses Java annotation processing and the standard Hilt Gradle
plugin; Kotlin/KSP and other Hilt versions are not covered.

## What is tested

The ordinary downstream API is preserved: `ProbeApplication` extends
`ParavoidAndroidApplication` with `@HiltAndroidApp`; `ProbeActivity` extends
`ComponentActivity` with `@AndroidEntryPoint`.

Two production-Application device tests passed on an API 36.1 emulator:

- Application and Activity field injection share the same `@Singleton` service,
  whose `@ApplicationContext` is the actual Android Application.
- A `@HiltViewModel` receives that singleton and a `SavedStateHandle`. Activity
  recreation preserves the ViewModel and its state, creates a new `@ActivityScoped`
  object, and does not rerun Application initialization.

These tests use the real app rather than `HiltTestApplication` or a replacement
DI graph. Activity recreation does not prove process-death restoration.

## Reproduce

Run from the repository root with JDK 17+, the same Android SDK as the main
sample, and `rg` available. This independent build needs `ANDROID_HOME` or its own
ignored `compatibility/hilt/local.properties`; the root local.properties alone
does not configure its SDK.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
bash compatibility/hilt/check.sh
```

The check builds the normal app and test APKs, requires the default Paravoid
manifest rejection, and builds the shell with the diagnostic manifest overlay.
Successful execution does not mean Hilt works in a payload at runtime.

To also run the normal-mode tests on an unlocked emulator/device:

```sh
ANDROID_SERIAL=emulator-5556 bash compatibility/hilt/check.sh --device
```

## Observed build blockers

1. The default Paravoid build fails in manifest processing because AndroidX brings
   `androidx.profileinstaller.ProfileInstallReceiver`. Its merged manifest also
   contains `androidx.startup.InitializationProvider` and `CoreComponentFactory`,
   which the current plugin does not support.
2. A probe-only manifest overlay removes those declarations. Packaging now
   succeeds: the packager accepts Hilt's generated intermediate Application base.
   Launching the unadapted shell on API 36.1 crashes in
   `ActivityComponentManager.createComponent()`: Hilt rejects `ShellApplication`
   because it is not a `GeneratedComponentManager`.

The earlier duplicate `META-INF/versions/9/module-info.class` blocker is fixed:
the packager ignores root and versioned Java module descriptors in both JAR and
directory inputs. Real duplicate classes still fail. This does not implement
general multi-release class selection.

Logs are in `build/compatibility/manifest.log` and `minimal-build.log` under
this directory. `-PhiltProbeMinimalManifest=true` enables the diagnostic overlay;
it is not a supported integration recipe. The resulting APK builds but cannot
launch its Hilt Activity without further adaptation.

## Further issues found by inspecting generated code and Hilt sources

The Activity lookup failure is now reproduced on a device; other integration
paths still need runtime validation:

- Hilt's `ActivityComponentManager.createComponent()` checks that the actual
  `activity.getApplication()` implements `GeneratedComponentManager`. The shell
  Application does not implement that interface; the generated implementation
  would live on the payload-side object instead.
- Hilt's generated component supplier passes the generated Application instance
  to `ApplicationContextModule(Context)`. After transformation that would be a
  ContextWrapper, not the process Application. Context bindings, component lookup,
  shared interface/class-loader identity, and initialization order need a deliberate
  integration design and tests.

Inspect `build/generated/hilt/component_sources/normalDebug/.../Hilt_ProbeApplication.java`
and the matching `hilt-android-2.57.2-sources.jar` for the source-level evidence.
The passing normal-mode test must remain the control when implementing support.

References: [Hilt Gradle transformation](https://dagger.dev/hilt/gradle-setup.html),
[Hilt applications](https://dagger.dev/hilt/application.html),
[Hilt ViewModels](https://dagger.dev/hilt/view-model.html).
