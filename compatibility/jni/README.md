# JNI and native library compatibility probe

For the newer production **relocated** native-library path, see
[PAYLOAD.md](PAYLOAD.md). The original checks below cover installed native libraries.

A conventional Android library dependency supplies a Java bridge and CMake-built
native code. The app uses Paravoid's normal and shell modes without native-loading
adapters in downstream source. Toolchain: AGP 8.13.2, NDK 27.0.12077973, CMake
3.22.1, C++17 and shared libc++. The fixture declares minSdk 29.

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/jni/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/jni/check.sh --device
```

Requires the pinned SDK tools; device checks additionally need Python 3.8+, adb
on PATH and a dedicated unlocked emulator. The script builds and checks both
uncompressed/APK-backed and compressed/extracted native libraries. It runs the
device matrix immediately after each build; the second build replaces the first
build's output paths. Distinct application IDs keep their installations separate.
Only fixture app data is cleared.

## Evidence

**104 passing device assertions on each of API 29 and API 36.1**, debug/x86_64: 13 checks × two
packaging modes × two native storage modes × cold/restored processes.

- JNI initialization during provider startup, before Application `onCreate`, and
  subsequent calls from the transformed Application.
- Ordinary `System.loadLibrary`, repeated loading, and one `JNI_OnLoad` per process.
- `RegisterNatives` plus exported-symbol lookup for other native methods.
- A linked `DT_NEEDED` dependency and a separate library opened with `dlopen`.
- C++ runtime use and a reported ABI supported by the device.
- `FindClass` and callbacks into app classes from ordinary JNI calls.
- A native-created attached thread using a cached class and an explicit cached
  Java classloader. Bare `FindClass` failure on that thread is an expected negative
  control in **both** modes, not a Paravoid failure.
- Direct ByteBuffer reads/writes and a Java exception thrown from native code.
- Installed manifest extraction flag and Java bridge classloader isolation.

The driver backgrounds and kills the exact app process, then verifies a new PID,
the restored saved-state run token, and the complete JNI checks again. Preferences
are observation output, not state restoration input.
The shared host process-death helper normally uses `run-as`. On API 29 debuggable
emulators only, a denied kill falls back to existing `su 0 kill -9` after validating
the fixture PID again (Google APIs rev13's SELinux denies the run-as signal).
Physical devices/non-debuggable images cannot use this fallback. No SELinux or
adbd changes, force-stop, or target-side instrumentation are used.

Artifact checks inspect all four `.so` files (`probe_jni`, `probe_dep`,
`probe_plugin`, `c++_shared`) for both x86_64 and arm64-v8a, their compression mode,
and the absence of native binaries from the embedded code payload. ARM64 is
build/packaging evidence only, not device execution evidence.

## Failure found and fixed

Initially both normal controls passed, while shell `System.loadLibrary` failed
with `UnsatisfiedLinkError`: its in-memory loader searched only system native
directories. The subsequent JNI checks failed because bridge initialization had
failed (36 shell failures across the original two storage/cold-restored matrices,
not 36 independent bugs).

The runtime now selects a compatible process ABI from installed base/split APK
entries and supplies the installed native-library directory and APK library paths
to the in-memory loader. It uses public APIs and does not copy executable files
into writable app storage. Seven host tests cover ABI preference, split paths,
irrelevant entries, incompatible ABIs, duplicate paths, unreadable archives and
the API floor. Split installation itself is not yet device-tested.

The native-search-path constructor is available from **API 29**. Native-bearing
shell apps must target a minimum of 29 or higher; runtime initialization rejects
older devices with an explicit error. Shell APK/AAB builds also validate AGP's
merged native libraries and reject minSdk below 29, listing the library paths and
suggesting a shell-flavor or defaultConfig minimum change. This includes transitive
dependencies and respects `packaging.jniLibs.excludes`. It is conservative across
ABIs: AGP merges libraries before final `ndk.abiFilters` filtering, so ABI filters
alone do not bypass the check. It does not inspect
arbitrary assets or prove native ABI/symbol compatibility. Normal packaging is
unaffected. Code-only application packaging retains its API 28 floor;
this does not raise the minimum for every app. The [API 29 boundary run](../API29.md)
now verifies both native storage modes at that floor, with no further loader changes.

Build-time validation regression evidence: 60 host tests and both plugin validators
pass. TestKit covers code-only API 28, adding JNI after an up-to-date build,
normal APK/AAB controls, shell APK/AAB rejection, shell-only minSdk 29 acceptance,
transitive AAR libraries, exclusions and conservative ABI-filter behavior. The real
Compose graph fails its retained minSdk 28 negative build and builds at shell
minSdk 29; this JNI fixture also rebuilds successfully in both storage modes with
artifact checks. No device rerun was needed for the build-only guard; device
evidence above predates that guard.

## Limits

Native binaries remain installed APK content. Replacing them, their dependencies,
or their ABI/symbol contract requires a shell update; this is not independent
native payload delivery. JNI names used by native code must remain compatible
with the payload. This probe does not establish safe native-library unloading or
hot replacement.

Still untested: real AAB/split installation, ARM devices, 32-bit/native-bridge
execution, 16 KiB page-size devices, multiple app classloaders loading the same
library, third-party native SDKs, and R8. CMake enables flexible-page-size support,
but compilation alone does not prove device compatibility. NativeActivity remains
outside this fixture. The revised fixed-manifest Activity contract does not by
itself establish NativeActivity loading or lifecycle compatibility.

References: [in-memory loader constructors](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader),
[Android JNI guidance](https://developer.android.com/ndk/guides/jni-tips).
