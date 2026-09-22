# Relocated native payload validation

```sh
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 \
  python3 compatibility/jni/payload-check.py --device
# On an emulator with a 32-bit zygote and x86 ABI support:
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 \
  python3 compatibility/jni/payload-check.py --device --abis x86
```

This uses the production signed embedded-resource shell task, minSdk 30, the
existing JNI fixture and its real process-death/restoration checks. It preserves
the ordinary normal APK as a control. Only dedicated emulator fixture apps are
installed/cleared; no physical-device/root fallback is allowed. The debuggable
API 29/30 images require their existing `su` to signal the verified fixture PID
because SELinux denies run-as sigkill; no security settings are changed.

## Verified on 2026-09-22

104 original JNI assertions plus native corruption/writability rejection and
test-only repair checks passed for:

- API 30 x86_64, with x86_64 + arm64-v8a packaged.
- API 36.1 x86_64, with x86_64 + arm64-v8a packaged.
- API 30 x86-only packaging, with a verified **32-bit app process**.

Each run covers both native storage modes of the original APK, both normal/shell,
cold/restored processes, provider/Application startup, linked `DT_NEEDED`
dependencies, `dlopen`, libc++, JNI registration/callbacks, attached native thread,
buffers and exceptions. Process maps prove shell execution uses the private
native payload directory. No application libraries remain under installed `lib/`.
Detailed evidence is in `build/payload-evidence-<serial>-<abis>.json`.

## Production behavior

The task extracts final APK library entries into `native-libraries.zip`, preserving
AGP packaging/exclusion results. ELF class/machine and supported ABI/name rules
are checked. Only fixed `libparavoid_abi.so` markers remain installed, one per
original ABI, preserving PackageManager's ABI and process-bitness selection.
Marker source, encoded artifacts and reproduction instructions are in
[`src/main/native`](../../paravoid-gradle-plugin/src/main/native/README.md).

At startup the APK-authenticated archive is verified and materialized in no-backup
storage. The first device-preferred ABI matching the process bitness is selected.
Libraries are extracted under an interprocess lock, made read-only and checked
against the archive's bytes every startup. That directory alone supplies payload
native paths; no fallback to installed application libraries or old generations.
Platform-provided linker dependencies continue to use Android's permitted paths.

The cache tests corrupt a native library without changing its size, then make an
intact copy writable. Both must prevent a new successful probe report. The driver
restores original bytes afterward; this is not an automatic runtime repair UI.

## Limits

This is embedded APK packaging, not independently signed VPK delivery/activation.
No ARM device execution, 16 KiB device execution, external native-plugin loading,
NativeActivity-specific flows, explicit paths derived from `nativeLibraryDir`,
isolated/direct-boot components or arbitrary dlopen search manipulation is proven.
Applications using installed library paths directly must not assume payload
libraries remain there. The archive profile currently caps its file at 256 MiB,
individual libraries at 256 MiB, entries at 4,096 and total expanded content at
2 GiB. Cache retention, leases for downloaded generations and recovery UI remain
future integration work. Ordinary DEX-only builds retain installed-native behavior.

The v1 filename rule was amended to allow `+`, required by `libc++_shared.so`.
Changes to supported native ABIs/marker sets must ultimately participate in the
full shell contract; the current resource-only baseline is not that full contract.
