# Compatibility exploration matrix

Passing means the pinned fixture and scenario passed, not that every API in that
library works. Device evidence so far is API 36.1/debug unless a fixture says
otherwise. Normal packaging is the control; production shell APKs are tested
without moving application libraries into the parent loader.

| Area | Evidence / status | Important remaining cases |
| --- | --- | --- |
| Java dependencies, annotations, generics, lambdas, ServiceLoader | Sample + `language` explicit/default discovery pass | Existing threads, shared pools, custom loaders |
| Application, provider, receiver, service startup | Sample + `hilt` probes pass | Direct boot, multiprocess, isolated services, bound/foreground services |
| Hilt plugin + Java annotation processing | `hilt`, pinned 2.57.2 | Fragments/Views, Hilt Workers, other versions |
| Kotlin, kapt, Compose compiler, navigation | `compose` passes | KSP, other Kotlin versions, deep links |
| Saved state and real process death | `compose` navigation counters + `language` generated Parcelable/Serializable pass | Other object graphs, payload-version changes |
| Room + default WorkManager factory | `storage` passes cold worker / durable writes | Migrations, retries, reboot, custom/Hilt factory configuration |
| Independent resources and assets | `resources` passes local A/B switching | Automatic app/library resource split, API 30 device coverage |
| Serialization/Parcelize compiler plugins, Kotlin reflection, dynamic proxies | [Language probe](language/README.md): 64 assertions pass, including background discovery | Other versions, reflection patterns, existing-thread loaders, R8 |
| ViewBinding/DataBinding, XML custom views, fragments | [Views probe](views/README.md): 170 assertions, including process death, fragment back stack and configured-context inflation | Nested fragments, custom factories, Hilt injection, other Context factories/overrides |
| Networking stacks and reflective adapters | Not tested | Retrofit/OkHttp, Gson/Moshi, coroutine execution, TLS |
| Native libraries / JNI | [JNI probe](jni/README.md): 104 assertions, APK-backed/extracted libraries, dependencies and native-thread callbacks; requires API 29+ | ARM/32-bit execution, actual split installation, 16 KiB devices, third-party SDKs |
| OS integration | Not tested | Permissions, activity results, notifications/PendingIntent, FileProvider, App Links |
| Third-party SDKs and Gradle transforms | Not tested | Firebase, crash reporting, bytecode instrumentation, SDK startup providers |
| Release/toolchain matrix | Limited APK/AAB packaging tests | R8/resource shrinking (currently rejected), AGP versions, configuration cache, Android API/ABI matrix |

## Test discipline

1. Pin versions and write an ordinary downstream-style fixture, changing only
   the packaging plugin setup and required Application superclass.
2. Test normal first, then shell. Record failure causes separately from emulator
   or test-driver failures. Verify defining loaders and preserve shell isolation.
3. Reproduce failures before adapters; add narrow fixes and regression tests.
4. Exercise cold entry and actual process death when relevant. Observe unique
   per-run results rather than trusting old preferences or build success alone.
5. Commit fixtures, fixes and documentation in small steps. Never relabel untested
   behavior as supported merely because a nearby scenario passed.

The automatic resource split remains a separate core implementation item. This
matrix is the exploration backlog, not a promise to support every Android app.
