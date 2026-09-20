# Hilt workers and custom WorkManager configuration

Pinned Java fixture: Dagger/Hilt 2.57.2, AndroidX Hilt Work/compiler 1.2.0,
WorkManager 2.10.1, Room/compiler 2.6.1 and Activity 1.10.1. It uses the optional
`com.lelloman.paravoid.hilt` plugin, one ordinary `@AndroidEntryPoint` Activity and
a `@HiltAndroidApp` subclass of `ParavoidAndroidApplication` implementing
`Configuration.Provider`. Both Hilt and Room use Java annotation processing.

```sh
# Build and run the standard lazy configuration path (known shell failure):
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/check.sh lazy --device

# Separate explicit-initialization experiment:
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
    bash compatibility/hilt-work/check.sh explicit --device
```

Omit `--device` for build-only checks. Use a dedicated unlocked emulator, Python
3.8+ and adb. Runs clear only fixture packages and kill only validated fixture
PIDs. Each policy has distinct application IDs, but build outputs share paths:
run its matching driver immediately after building, as `check.sh` does.

## Confirmed lazy-initialization failure

On API 36.1/debug/x86_64, normal packaging passes the complete three-process
sequence: enqueue, kill writer, cold JobService/Hilt Worker, injected repository
Room update, kill worker, fresh Activity reading the committed update.

Shell packaging fails at the first `WorkManager.getInstance(context)` before
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
adapter for the unsupported automatic Configuration.Provider lookup.

References: [HiltWorkerFactory](https://developer.android.com/reference/androidx/hilt/work/HiltWorkerFactory),
[custom WorkManager configuration](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration).
