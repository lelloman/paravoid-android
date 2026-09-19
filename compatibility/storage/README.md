# Room + WorkManager probe

An independent Java app using Room 2.6.1 (annotation processing) and WorkManager
2.10.1. It uses the unmodified dependency manifests, default AndroidX Startup
initialization and default Worker factory. There is no custom Application or Hilt
adapter in this fixture.

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/storage/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5592 \
  bash compatibility/storage/check.sh --device
```

Device checks require Python 3, adb and a dedicated API 36.1 emulator. The script
installs and **clears test data for only the two storage fixture packages**.

Both normal and shell packaging passed this sequence:

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
