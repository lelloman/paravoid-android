# Room + WorkManager probe

An independent Java app using Room 2.6.1 (annotation processing) and WorkManager
2.10.1. It uses the unmodified dependency manifests, default AndroidX Startup
initialization and default Worker factory. There is no custom Application or Hilt
adapter in the default mode.

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/storage/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5592 \
  bash compatibility/storage/check.sh --device
```

Device checks require Python 3, adb and a dedicated emulator. The script
installs and **clears test data for only the two storage fixture packages**.

Both normal and shell packaging passed this sequence on API 28 and API 36.1:

1. Activity writes a unique Room row and enqueues delayed, persisted work.
2. The script backgrounds the app and kills its exact PID without force-stopping
   the package. Android's JobScheduler starts a fresh process without opening an
   Activity. If necessary, the script explicitly dispatches the registered job
   after WorkManager's initial delay has elapsed.
3. WorkManager's default factory creates the Worker. It reads the prior process's
   row, updates it, reads it back and returns success.
4. After killing the worker process, a fresh Activity reads the committed update.

The Worker verifies its defining classloader and Room's generated database
implementation loader. Unique per-run tokens and different writer/worker PIDs
guard against stale success reports. The worker execution and database operations
run off the main thread.

Not covered: Hilt Worker injection or Application `Configuration.Provider`, Room
migrations, Kotlin/KSP Room models, retry/backoff, periodic/foreground work, reboot,
device-idle scheduling policy, multiprocess work or payload-update compatibility.
Job dispatch here tests cold component loading, not delivery timing guarantees.

The separate [two-version migration fixture](../migrations/README.md) verifies a
manual Room 1→2 schema migration, generated DAO loading, transaction/index checks,
rejected code downgrade and subsequent v2 recovery on API 36.1. Its shell APK
stays fixed while a fixture-only hook selects between prebundled code versions;
it is not production external-update or database-rollback support.

The separate [Hilt Work probe](../hilt-work/README.md) now covers Hilt Worker
injection: lazy configuration passes both modes with `paravoid-work`; without that
optional plugin, shell mode needs explicit initialization. The Hilt custom path
is separately verified on API 28, 29 and 36.1; default-factory results alone are
not evidence for that combination.

The driver uses unnamespaced JobScheduler IDs/dispatch below API 34. On API 28,
reopening the old Activity task reused its original enqueue Intent and attempted
a duplicate Room insert in **normal packaging too**. The final reader now uses
NEW_TASK | CLEAR_TASK so its verification Intent reaches a fresh Activity. This
tests durable database state, not Activity task restoration. Launch completion
uses per-run reports rather than `am start -W` draw waits.

## Non-Hilt custom configuration

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/storage/check.sh --configured --device
```

The separate `--configured` mode uses distinct package IDs, a
`ParavoidAndroidApplication` implementing `Configuration.Provider`, a custom
WorkerFactory, and `com.lelloman.paravoid.work`. There is no Hilt dependency or
manual initialization. Its manifest removes the default WorkManager initializer.
Both modes pass on API 28, 29 and 36.1/debug/x86_64: configuration/factory callbacks run in the
new worker PID after Application onCreate, without an Activity in that process,
and the factory receives the real Application context. The same Room/process-death
sequence remains in force. These are separate custom-configuration runs, not
inferences from the default-factory fixture. The driver uses the shared guarded
process-death helper; API 29's debuggable-emulator fallback is described in
[the integration report](../../paravoid-work/README.md).

For build-only checks use `check.sh --configured`. Normal and configured builds
share APK output paths: run each matching driver immediately after building.
`-PworkProbeDisableIntegration` retains an unadapted configured build;
`-PworkProbeEnableIntegration` enables the adapter on the default-initializer
fixture to regression-test apps without a custom Application. These are fixture
switches, not public plugin options.
