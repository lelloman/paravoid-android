# Hilt workers and custom WorkManager configuration

Pinned Java fixture: Dagger/Hilt 2.57.2, AndroidX Hilt Work/compiler 1.2.0,
WorkManager 2.10.1, Room/compiler 2.6.1 and Activity 1.10.1. It uses the optional
`com.lelloman.paravoid.hilt` and `com.lelloman.paravoid.work` plugins, one ordinary `@AndroidEntryPoint` Activity and
a `@HiltAndroidApp` subclass of `ParavoidAndroidApplication` implementing
`Configuration.Provider`. Both Hilt and Room use Java annotation processing.

```sh
# Build and run the standard lazy configuration path with paravoid-work:
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/check.sh lazy --device

# Verified explicit-initialization workaround:
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/check.sh explicit --device
```

Omit `--device` for build-only checks. Use a dedicated unlocked emulator, Python
3.8+ and adb. Runs clear only fixture packages and kill only validated fixture
PIDs. Each policy has distinct application IDs, but build outputs share paths:
run its matching driver immediately after building, as `check.sh` does.
Installations use `--no-streaming`: one repeat hit an APK v2 digest error during
streamed installation, while host `apksigner verify` and a non-streamed install of
the same file passed. That interrupted repeat is not counted as a full pass.

## Automatic configuration with paravoid-work

With the optional [paravoid-work integration](../../paravoid-work/README.md), lazy
configuration now passes in normal **and** shell packaging on API 28, 29 and
36.1/debug/x86_64.
No manual initialization call is needed: the fixture's `EXPLICIT_INITIALIZATION`
flag is false. The same cold-worker/three-process Room assertions run in both modes.
The adapter resolves only the configuration owner from the payload Application;
the real Application context passed to WorkManager remains unchanged.
API 28/29 use the same pinned production APKs and assertions, with no downstream
initialization workaround. API 29 uses the documented guarded emulator process-kill
fallback. Explicit-initialization and plugin opt-out device results below remain
API 36.1 evidence, not claims that those separate modes were rerun on older APIs.

Retained negative reproducer without that integration (normal passes, shell fails):

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew -p compatibility/hilt-work \
    -PworkProbeDisableIntegration assembleNormalDebug assembleParavoidAndroidDebug
ANDROID_SERIAL=emulator-5584 python3 compatibility/hilt-work/device-check.py lazy
```

Rebuild using `check.sh lazy` afterward to restore adapted artifacts. Plugin flags
are build-time fixture switches, not runtime behavior or production plugin options.

## Original failure without the optional integration

On API 36.1/debug/x86_64, normal packaging passes the complete three-process
sequence: enqueue, kill writer, cold JobService/Hilt Worker, injected repository
Room update, kill worker, fresh Activity reading the committed update.

Without `paravoid-work`, shell packaging fails at the first `WorkManager.getInstance(context)` before
enqueue, with `WorkManager is not initialized properly` / Application does not
implement `Configuration.Provider`. The driver exits nonzero; this is not an
expected-failure result counted as a pass.

In pinned WorkManager 2.10.1, `WorkManagerImpl.getInstance(Context)` obtains
`context.getApplicationContext()` and checks `instanceof Configuration.Provider`.
That object is the real `ShellApplication`, not the transformed payload Application
which implements the interface. Existing Hilt lookup rewriting does not adapt
WorkManager's check. The failure is Application configuration discovery, not
evidence that assisted injection or Room itself is broken.

The default WorkManager AndroidX Startup initializer is deliberately removed in
both policies, as required for custom configuration. Re-enabling its default
factory would not solve Hilt Worker creation. `ProbeWorker` has only its generated
assisted-injection constructor, not the default reflective two-argument one.

## Assertions and boundaries

- Application, Activity and Worker share the injected singleton within each process.
- Worker process has a new graph/PID and has never created an Activity.
- Hilt configuration is requested exactly once in that worker process, after
  Application field injection. Application initialization has completed.
- Injected `Application` and `@ApplicationContext` refer to the same real object.
- Room generated implementation and Worker use the expected loader; the shell
  parent cannot load the Worker.
- Worker reads the earlier process's row, commits its own PID/graph, and a third
  process reads it. Observation preferences do not supply database contents.
- Forced OS job dispatch, if necessary, occurs after the requested initial delay;
  this tests cold loading/injection, not scheduling-time guarantees.

The explicit policy calls `WorkManager.initialize(this, getWorkManagerConfiguration())`
after `super.onCreate()` (Hilt injection). It remains a separate experiment, not an
adapter for automatic Configuration.Provider lookup; that is now provided by
the optional `paravoid-work` plugin.

## Verified workaround

On API 36.1/debug/x86_64, **both normal and shell pass** the same complete
three-process test with explicit initialization. Two complete runs pass with fresh
tokens/graphs (the final run uses non-streamed installs). Both-mode lint also passes.
The generated Hilt assisted factory, injected singleton repository, real
Application/context bindings and Room writes work once configuration is supplied.
The original explicit-initialization experiment required no production runtime
or `paravoid-hilt` changes. The new automatic path is a separate optional module.

An app using this workaround must:

1. Apply the optional Paravoid Hilt integration and the tested Hilt toolchain.
2. Remove the default WorkManager initializer in the merged manifest, not the
   entire AndroidX Startup provider (other initializers may need it).
3. Inject `HiltWorkerFactory`, then initialize WorkManager exactly once from its
   `ParavoidAndroidApplication.onCreate()`, **after** `super.onCreate()`:

   ```java
   @Override public void onCreate() {
       super.onCreate();
       WorkManager.initialize(this, getWorkManagerConfiguration());
   }
   ```

This happens on every fresh app process, including JobService-only startup. Calling
initialize only from an Activity would not satisfy that requirement. Configuration
must set the injected factory; restoring the default initializer/default factory
is not equivalent. The fixture uses the same source in both packaging modes.

The separate [chain/retry/cancellation stress probe](STRESS.md) passes on API 36.1
in both modes: a Hilt root retries across process death, passes Data to an ordinary
reflectively constructed dependent, and retains successful state after restart.
Active cancellation verifies `onStopped`, blocked-dependent cancellation and
retained parent state after restart; cancelled dependents may be pruned.

Still untested: provider-time WorkManager access before Application `onCreate`,
Hilt `@ApplicationContext` use beyond this probe, Kotlin/kapt/KSP worker generation,
CoroutineWorker, broader retry policies/DAGs/constraints, periodic/foreground work,
reboot, multiprocess, payload-version changes, release/R8, other OS/library versions
and ABIs. Explicit initialization is not a fix for arbitrary libraries expecting
interfaces on the real Application. WorkManager lazy lookup requires
`com.lelloman.paravoid.work`; enabling only `com.lelloman.paravoid.hilt` is insufficient.

References: [HiltWorkerFactory](https://developer.android.com/reference/androidx/hilt/work/HiltWorkerFactory),
[custom WorkManager configuration](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration).
