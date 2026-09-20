# JNI and native library compatibility probe

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

**104 passing device assertions** on API 36.1/debug, x86_64: 13 checks × two
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
older devices with an explicit error. There is no build-time minimum-SDK check for
native dependencies yet. Code-only application packaging retains its API 28 floor;
this does not raise the minimum for every app. API 29 itself has not been
device-tested in this batch.

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
outside this fixture and the existing one-Activity application contract.

References: [in-memory loader constructors](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader),
[Android JNI guidance](https://developer.android.com/ndk/guides/jni-tips).
