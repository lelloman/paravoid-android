# API 29 native-loading boundary

Verified debug production APKs, normal and shell packaging, on Android 10.
This is a selected boundary test, not the entire compatibility matrix.

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/api29-check.sh
python3 -m unittest discover -s compatibility -p test_device_process.py
```

The runner requires an explicit dedicated, unlocked API 29 emulator. It builds
and runs Compose first, then both JNI native-storage variants. Only fixture app
data is cleared. Python 3.8+, adb, the normal Gradle toolchain and the JNI fixture's
pinned NDK/CMake are required. Device provisioning is separate.

## Results

| Probe | Normal and shell result |
| --- | --- |
| Compose / Hilt / Navigation | Resources, navigation-scoped ViewModels, shared singleton, rotation and real process-death saved-state restoration pass |
| JNI, uncompressed APK-backed libraries | 52 assertions pass across both packaging modes and cold/restored processes |
| JNI, compressed/extracted libraries | 52 assertions pass across both packaging modes and cold/restored processes |

All 104 JNI assertions pass: provider/Application startup, ordinary library loads,
RegisterNatives/exported symbols, linked dependencies, dlopen, native-thread
callbacks, direct buffers, exceptions, ABI/runtime, extraction and loader isolation.
Artifact checks additionally verify both x86_64 and arm64-v8a library entries;
only x86_64 executes here. See [JNI details](jni/README.md).

The same pinned Compose graph that fails the native-library guard on API 28
successfully starts and completes its lifecycle checks on API 29. Its transitive
`libandroidx.graphics.path.so` remains installed APK content. This validates this
graph at the native-loading floor, not every Compose native code path.
No production runtime/plugin changes were necessary.

## Driver portability finding

The initial normal Compose run could not kill its process: the image's SELinux
policy denied `runas_app` sending `sigkill` to `untrusted_app`, despite matching
app UID. The process remained alive; this was not an application failure.

Compose and JNI now share an exact-PID helper. It tries `run-as` first; only on an
API 29 debuggable emulator may it fall back to existing `su 0 kill -9`, after
rechecking the fixture PID. It never roots adbd, changes SELinux, force-stops the
package or injects target-side instrumentation. Five host tests cover the default
path, fallback, invalid targets, changed PIDs and refusal on other devices/images.
Fresh PIDs and saved-state/object-identity checks still establish actual restoration.

## Environment and limits

Google APIs x86_64 API 29 system image revision 13, emulator 36.3.10, 2 GiB RAM,
6 GiB data, 1080×1920/density 420, 4096-byte pages. Official SDK archive
`google_apis/x86_64-29_r13.zip`, SHA-1
`50c2953ab13f312ed797a961577ee74a911d087d`, checked against SDK repository metadata.
Fingerprint: `google/sdk_gphone_x86_64/generic_x86_64:10/QSR1.211112.011/13135432:userdebug/dev-keys`.
The image was provisioned in an isolated temporary AVD, without modifying other AVDs.

Not covered: other fixtures on API 29, release/R8, AAB/split installation, ARM or
32-bit execution, 16 KiB pages, physical/OEM devices, arbitrary third-party native
SDKs, native hot replacement or payload-version migration. The runtime API 29
native-library floor is unchanged; native-bearing minSdk violations still lack a
build-time diagnostic. API 28 code-only support is unchanged.
