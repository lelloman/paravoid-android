# Compatibility exploration matrix

Implementation priority is now the [packaging-first roadmap](../ROADMAP.md).
This matrix supplies regression coverage and tracks known gaps; exhausting its
backlog is not a prerequisite for complete packaging or real-app integration.

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
| APK-provisioned update keys | [Provisioning probe](provisioning/README.md): 10 host tests and 24 device stages on API 30 and 36.1; normal/shell public/key requests, revocation and APK-delivered replacement with app data retained | Harmless unsigned downloads only; provisional APK record, no issuer validation, signed VPK delivery, empty bootstrap or activation |
| Signed update-discovery experiment | [Signed probe](provisioning/SIGNED.md): 16 host tests and 114 device stages on API 30 and 36.1; independent Python signing/Android verification, issuer-signed grants, persisted replay/freshness checks, hostile transfers and redirects | Fixture profile and harmless data only; no VPK inventory, finalized trust/rotation policy, capability negotiation or production updater integration |
| Signed component-inventory experiment | [Archive probe](provisioning/ARCHIVE.md): 22 host tests and 136 device stages on API 30 and 36.1; separately signed manifest, inventory coverage, component integrity, strict stored-ZIP checks and retained-file preservation | Opaque role-labelled marker bytes only; no real DEX/resource/native loading, complete VPK requirements, compressed containers, extraction or activation |
| Real downstream app: Pezzottify | Dedicated integration branch builds AGP 9.0.0/Gradle 9.1.0 phone/debug normal + shell, with Hilt; API-30 and API-36.1/x86_64 logged-out startup/restart, Compose login UI, rotation, isolated callback routing and Room integrity pass; Androidoscopy initializes | DEX-only packaging, no real login/token exchange or library Activity UI validation; authenticated sync, playback, assistant JNI execution and complete packaging remain unproved. See [roadmap status](../ROADMAP.md#2-integrate-a-real-downstream-app) |
| Multiple app/library Activities | [Real AppAuth/Androidoscopy fixture](library-activities/README.md): normal/shell build, lint, manifest preservation and payload-only implementation checks pass; per-Activity hooks/shared-base handling have host tests | One MAIN launcher, no aliases; real SDK flows, external entry/results/restoration need device coverage; multiple launcher routing and shell-contract diff checks pending; declaration/pinned-resource changes require a new shell |
| Java dependencies, annotations, generics, lambdas, ServiceLoader | Sample + `language` explicit/default discovery pass | Existing threads, shared pools, custom loaders |
| Application, provider, receiver, service startup | Sample + `hilt`; [Binder/AIDL](os/BINDER.md) binding Intents, explicit-loader Bundles and death recovery; [foreground dataSync](os/FOREGROUND.md) cold start, Intent redelivery and stop/restart pass; Direct Boot and named workers tracked below | Isolated services, other foreground types/timeouts, client death and wire-version changes |
| Named worker process | [Multiprocess probe](os/MULTIPROCESS.md): all 12 external/same-app Binder connection stages pass on API 36.1, with independent Application/loader initialization and worker death/recovery | Same UID, installed manifest declaration; no isolated services, multiprocess libraries/storage, remote providers/receivers, startup races or other OS/ABI coverage |
| Hilt plugin + Java annotation processing | `hilt`, pinned 2.57.2; Hilt Workers with optional `paravoid-work` below | Fragments/Views, other versions |
| Kotlin, kapt, KSP, Compose compiler, navigation | `compose` + `network` Moshi KSP-generated ordinary models pass | Moshi 1.15.2 generated qualifiers fail with pinned KSP2 in both modes; other processors/versions, deep links |
| Saved state and real process death | `compose` navigation counters + `language` generated Parcelable/Serializable pass | Other object graphs, payload-version changes |
| Room + default WorkManager factory | `storage` passes cold worker / durable writes | Migrations, retries, reboot; custom/Hilt configuration tracked separately below |
| Room code/schema upgrades | [Migration probe](migrations/README.md): manual v1→v2 migration, generated DAOs, index/transaction checks, rejected v1 downgrade and v2 recovery; ten stages pass on API 36.1 with a fixed shell APK | Fixture-only prebundled selector, not external updates; no reverse migration, interrupted-migration recovery, concurrent processes, auto-migrations or other APIs/ABIs |
| Hilt Worker + custom WorkManager configuration | [Hilt Work probe](hilt-work/README.md): lazy configuration and cold injected worker pass with optional `paravoid-work`; non-Hilt configured storage control passes too. [Stress probe](hilt-work/STRESS.md): mixed-worker retry chain and active cancellation across process death pass on API 36.1 | Plugin required for lazy shell lookup; pinned WorkManager 2.10.1; broader DAGs/constraints, CoroutineWorker, foreground work, other versions; new stress scenarios not yet verified on API 28/29 |
| Independent resources and assets | `resources` passes local A/B switching | Automatic app/library resource split, API 30 device coverage |
| Shared resource-ID split | [Same-package proof](resource-split/README.md): AAPT2 app/static-library linking and a pinned-only `0x7f` installed table; early Application/provider and A/B/A resource lookup pass on API 30 and 36.1 (12 device stages each) | Hand-selected inputs and fixture loading hook; no automatic AGP split, persistent ledger, semantic shell-contract checks, Compose/system-resource/worker coverage in this fixture |
| Serialization/Parcelize compiler plugins, Kotlin reflection, dynamic proxies | [Language probe](language/README.md): 64 assertions pass, including background discovery | Other versions, reflection patterns, existing-thread loaders, R8 |
| ViewBinding/DataBinding, XML custom views, fragments | [Views probe](views/README.md): 170 assertions, including process death, fragment back stack and configured-context inflation | Nested fragments, custom factories, Hilt injection, other Context factories/overrides |
| Networking stacks and reflective adapters | [Network probe](network/README.md): 76 assertions and 8 server audits; Retrofit/OkHttp, Gson/Moshi, cancellation, cache, TLS rejection controls | HTTP/2, WebSockets, public DNS/proxies, pinning/mTLS, authentication refresh, other versions |
| Native libraries / JNI | [JNI probe](jni/README.md): 104 assertions, APK-backed/extracted libraries, dependencies and native-thread callbacks; requires API 29+ | ARM/32-bit execution, actual split installation, 16 KiB devices, third-party SDKs |
| OS integration | [OS probe](os/README.md): 40 FileProvider/framework-result checks; cold notification action/content Parcelables; 8 AndroidX result success/cancel scenarios including process death; [alarm probe](os/ALARMS.md) for exact-access denial, cold delivery, replacement and cancellation; [alarm lifecycle](os/ALARM-LIFECYCLE.md) for revocation/regrant, forced deep idle and ordinary reboot recovery on API 36.1 | Alarm quotas/UI/abrupt-power-loss recovery; broader permission contracts, mutable tokens/RemoteInput, existing-task notification delivery, other result contracts, App Links |
| Direct Boot | [PIN-locked probe](direct-boot/README.md): Application/receiver payload loading, DE preferences, CE rejection, real alarm/Parcelable before unlock; two unlock callbacks with one deferred initialization in both modes on API 36.1 | Application must defer CE-dependent work; no third-party startup/DI/providers, process death between stages, multi-user, remote payload cache or other OS/ABI coverage |
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
