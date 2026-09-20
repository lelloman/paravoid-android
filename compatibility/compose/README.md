# Compose + Navigation + Hilt probe

An independent, two-screen Kotlin app with one ordinary `ComponentActivity` and a
`ParavoidAndroidApplication` subclass. Dependencies are pinned: Kotlin/Compose
compiler 2.2.21, Material3 1.3.2, Activity Compose 1.10.1, Navigation Compose 2.8.9,
AndroidX Hilt Navigation Compose 1.2.0 and Hilt 2.57.2, using kapt.

Both packaging modes passed the checks below on API 36.1.
On API 28, normal packaging passes, but shell startup fails: this dependency
graph packages `libandroidx.graphics.path.so`, and Paravoid's native-library
loading requires API 29+. This is a transitive native dependency, not a Hilt
injection or saved-state failure. API 29 execution remains unverified here.

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
Running on API 28 intentionally still fails at shell startup; it is not skipped
or counted as a successful compatibility run.

## Checks

- Compose Material theme, installed string resource access and rendering.
- A separate `hiltViewModel()` per navigation entry, sharing the Application's
  singleton. No app-owned Hilt lookup bridge.
- Both `SavedStateHandle` and `rememberSaveable` counters survive rotation.
- Rotation creates a new Activity but retains the ViewModel and singleton.
- Background the app, wait for `onSaveInstanceState`, kill only its validated PID
  through `run-as`, then reopen its launcher task. A genuinely new process restores
  the detail destination and both counters; its Activity, ViewModel and graph are new.
- System Back restores the list's independent saved state. Opening detail again
  creates a fresh navigation-scoped ViewModel and counters.
- The shell APK excludes the app/Compose classes from installed DEX, and its bundle
  contains multiple numbered DEX files with format/count metadata.

Preferences are a **write-only observation channel**, never a source of restored
state. A PID change and newly generated object IDs prevent Activity recreation
from masquerading as process death.

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
