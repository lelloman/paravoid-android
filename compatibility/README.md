# Compatibility exploration matrix

Passing means the pinned fixture and scenario passed, not that every API in that
library works. Device evidence so far is API 36.1/debug unless a fixture says
otherwise. Normal packaging is the control; production shell APKs are tested
without moving application libraries into the parent loader.

The [API 28 baseline](API28.md) additionally verifies selected OS, Binder,
language, Views, Room/WorkManager (including Hilt/non-Hilt custom configuration)
and networking scenarios on Android 9, in both
packaging modes. Compose passed normally there but its shell failed the API 29+
native-library guard due to transitive `libandroidx.graphics.path.so`. Builds now
reject native-bearing shell variants below minSdk 29; the Compose shell flavor
declares 29 while normal remains 28. The baseline
found and fixed eager saved-state decoding before `onCreate`. Its runner reports explicit
skips/omissions; newer-device evidence elsewhere is not implied to cover API 28.

The [API 29 native-loading boundary](API29.md) passes Compose/Hilt/Navigation
and all 104 JNI assertions in both packaging modes, including APK-backed and
extracted libraries and process-death restoration. It also verifies Hilt and
non-Hilt lazy WorkManager configuration with `paravoid-work`. Other matrix rows are not
implicitly verified on API 29.

| Area | Evidence / status | Important remaining cases |
| --- | --- | --- |
| Java dependencies, annotations, generics, lambdas, ServiceLoader | Sample + `language` explicit/default discovery pass | Existing threads, shared pools, custom loaders |
| Application, provider, receiver, service startup | Sample + `hilt`; [Binder/AIDL](os/BINDER.md) binding Intents, explicit-loader Bundles and death recovery; [foreground dataSync](os/FOREGROUND.md) cold start, Intent redelivery and stop/restart pass | Direct boot, multiprocess, isolated services, other foreground types/timeouts, client death and wire-version changes |
| Hilt plugin + Java annotation processing | `hilt`, pinned 2.57.2; Hilt Workers with optional `paravoid-work` below | Fragments/Views, other versions |
| Kotlin, kapt, KSP, Compose compiler, navigation | `compose` + `network` Moshi KSP-generated ordinary models pass | Moshi 1.15.2 generated qualifiers fail with pinned KSP2 in both modes; other processors/versions, deep links |
| Saved state and real process death | `compose` navigation counters + `language` generated Parcelable/Serializable pass | Other object graphs, payload-version changes |
| Room + default WorkManager factory | `storage` passes cold worker / durable writes | Migrations, retries, reboot; custom/Hilt configuration tracked separately below |
| Hilt Worker + custom WorkManager configuration | [Hilt Work probe](hilt-work/README.md): lazy configuration and cold injected worker pass with optional `paravoid-work`; non-Hilt configured storage control passes too | Plugin required for lazy shell lookup; pinned WorkManager 2.10.1; retries/chains, CoroutineWorker, foreground work, other versions |
| Independent resources and assets | `resources` passes local A/B switching | Automatic app/library resource split, API 30 device coverage |
| Serialization/Parcelize compiler plugins, Kotlin reflection, dynamic proxies | [Language probe](language/README.md): 64 assertions pass, including background discovery | Other versions, reflection patterns, existing-thread loaders, R8 |
| ViewBinding/DataBinding, XML custom views, fragments | [Views probe](views/README.md): 170 assertions, including process death, fragment back stack and configured-context inflation | Nested fragments, custom factories, Hilt injection, other Context factories/overrides |
| Networking stacks and reflective adapters | [Network probe](network/README.md): 76 assertions and 8 server audits; Retrofit/OkHttp, Gson/Moshi, cancellation, cache, TLS rejection controls | HTTP/2, WebSockets, public DNS/proxies, pinning/mTLS, authentication refresh, other versions |
| Native libraries / JNI | [JNI probe](jni/README.md): 104 assertions, APK-backed/extracted libraries, dependencies and native-thread callbacks; requires API 29+ | ARM/32-bit execution, actual split installation, 16 KiB devices, third-party SDKs |
| OS integration | [OS probe](os/README.md): 40 FileProvider/framework-result checks; cold notification action/content Parcelables; 8 AndroidX result success/cancel scenarios including process death | Permission contracts, mutable tokens/RemoteInput, existing-task notification delivery, other result contracts, App Links |
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
