# Compose + Navigation + Hilt probe

An independent, two-screen Kotlin app with one ordinary `ComponentActivity` and a
`ParavoidAndroidApplication` subclass. Dependencies are pinned: Kotlin/Compose
compiler 2.2.21, Material3 1.3.2, Activity Compose 1.10.1, Navigation Compose 2.8.9,
AndroidX Hilt Navigation Compose 1.2.0 and Hilt 2.57.2, using kapt.

Both packaging modes passed the checks below on API 29 and API 36.1.
The earlier API 28 experiment passed normal packaging but failed shell startup: this dependency
graph packages `libandroidx.graphics.path.so`, and Paravoid's native-library
loading requires API 29+. This is a transitive native dependency, not a Hilt
injection or saved-state failure. The [API 29 boundary run](../API29.md) passes
without changing the dependency graph or the production runtime. Builds now catch
this incompatibility: the shell flavor declares minSdk 29; normal remains 28.

Retained negative build (expected failure naming `libandroidx.graphics.path.so`):

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew -p compatibility/compose \
  assembleParavoidAndroidDebug -PshellMinSdk=28
```

`shellMinSdk` is a test-fixture override, not a Paravoid plugin option. Downstream
apps set `android.productFlavors.paravoidAndroid.minSdk = 29` or raise defaultConfig.

## Run

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/compose/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5592 \
  bash compatibility/compose/check.sh --device
```

Device checks need Python 3.8+, adb and a dedicated, unlocked emulator
with sufficient free storage. Do not share it with other UI test runs. No test
runner, Kotlin classes or Hilt types are added to the shell. The script installs
both fixture APKs, clears only their test data, taps real accessibility nodes and
restores rotation settings. API 28 rotation uses system settings, not newer `wm`
commands; launch readiness is checked by PID/state rather than a launcher draw wait.
The current shell APK cannot install on API 28; its historical runtime failure is
now prevented by build-time validation and the declared minimum SDK.

## Checks

- Compose Material theme, installed string resource access and rendering.
- A separate `hiltViewModel()` per navigation entry, sharing the Application's
  singleton. No app-owned Hilt lookup bridge.
- Both `SavedStateHandle` and `rememberSaveable` counters survive rotation.
- Rotation creates a new Activity but retains the ViewModel and singleton.
- Background the app, wait for `onSaveInstanceState`, kill only its validated PID
  through the host process-death helper, then reopen its launcher task. A genuinely new process restores
  the detail destination and both counters; its Activity, ViewModel and graph are new.
- System Back restores the list's independent saved state. Opening detail again
  creates a fresh navigation-scoped ViewModel and counters.
- The shell APK excludes the app/Compose classes from installed DEX, and its bundle
  contains multiple numbered DEX files with format/count metadata.

Preferences are a **write-only observation channel**, never a source of restored
state. A PID change and newly generated object IDs prevent Activity recreation
from masquerading as process death.

The helper normally uses `run-as`. API 29 Google APIs image revision 13 denies
that cross-domain signal under SELinux, even for the same app UID. Only on an
API 29 debuggable emulator, after rechecking the exact fixture PID, the driver
falls back to the image's existing `su 0 kill -9`. It does not root adbd, alter
SELinux, force-stop the package or add target-side test code. The fallback is
unavailable on physical devices/non-debuggable images. Host guard tests:
`python3 -m unittest discover -s compatibility -p test_device_process.py`.

## Issues exposed

The dependency graph exceeded the original single-DEX limit. Application packaging
now supports bounded multi-DEX bundles loaded together by one in-memory loader.

After that, shell process-death restoration exposed a wrong saved-state loader:
Android assigned the installed loader to the Bundle and could not unmarshal
Compose's `ParcelableSnapshotMutableIntState`. The core plugin prepares the saved
Bundle at payload `onCreate` entry, before user or AndroidX initialization. This is
separate from the existing Activity `getClassLoader()` override and requires no
downstream code change.

This probe does not establish arbitrary custom Parcelable restoration, state
compatibility across payload updates, KSP, deep links, shrinking or other Android
versions. Resources remain installed in the APK, not independently updated.

References: [Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler),
[Hilt with Jetpack](https://developer.android.com/training/dependency-injection/hilt-jetpack).
