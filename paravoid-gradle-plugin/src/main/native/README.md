# Shell ABI markers

These dependency-free shared objects preserve PackageManager ABI/process-bitness
selection after application native libraries move out of the installed `lib/`
directory. They are never loaded by the app. The reserved name is
`libparavoid_abi.so`; a downstream collision is rejected.

Sources are ours (`abi-marker.c`). The base64-encoded build outputs under
`src/main/resources/com/lelloman/paravoid/abi/` let plugin consumers package native
dependencies without installing an NDK merely to compile the shell marker.

Reproduction toolchain: Android NDK 27.0.12077973, Linux x86_64 clang. For each target:

```sh
clang --target=TARGET -nostdlib -shared -fno-ident \
  -Wl,--build-id=none,-soname,libparavoid_abi.so,-z,max-page-size=16384 -s \
  abi-marker.c -o marker.so
base64 -w 76 marker.so
```

Targets: `aarch64-linux-android30` → arm64-v8a,
`armv7a-linux-androideabi30` → armeabi-v7a, `i686-linux-android30` → x86,
`x86_64-linux-android30` → x86_64. Use the NDK's clang, not a host compiler.
The ELF machine/class, absence of dependencies and 16 KiB LOAD alignment should be
verified with the NDK's `llvm-readelf -h -l -d` before replacing these artifacts.

PackageManager's native-library ABI behavior is documented in
[Android ABIs](https://developer.android.com/ndk/guides/abis) and implemented by
[PackageAbiHelperImpl](https://android.googlesource.com/platform/frameworks/base/+/android11-release/services/core/java/com/android/server/pm/PackageAbiHelperImpl.java).
