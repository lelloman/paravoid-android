# Foreground Service probe

Synthetic local-file-processing `dataSync` Service, compile/target SDK 36. The
driver requires an unlocked API 34+ emulator (English UI), Python 3 and adb.

```sh
bash compatibility/os/check.sh
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/foreground-device-check.py
```

The full OS `--device` suite includes this probe. It installs/clears only the
fixture target and peer packages. Notification permission is pre-granted via adb;
permission dialogs and denied-notification behavior are separate coverage.

## Scenario

API 36.1 x86_64/debug: all three starts and both explicit stops pass in normal and
shell packaging. The Service defining-loader fix is exercised on initial and
redelivered start Intents; no additional foreground-specific adapter was needed.

A button in the visible peer app calls `startForegroundService()` on a cold
target, protected by the existing signature permission. No target Activity is
launched. A custom shared-wire Parcelable reaches `onStartCommand` without manual
extras-loader repair, and its Unicode text is written to a private checkpoint.

The target declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and the
`dataSync` type. It promptly calls `startForeground`, then observes its actual
published notification/channel/foreground flag and runtime Service type. It
returns `START_REDELIVER_INTENT`.

The driver kills only the verified target PID (not force-stop). Android must
restart the Service in a new process/instance and redeliver the Parcelable with
`START_FLAG_REDELIVERY`; the peer stays alive. An explicit stop then verifies
destruction and notification removal. A fresh user-triggered start creates a new
Service instance with no redelivery flag, followed by another clean stop.
Application/Service PIDs, run tokens, instance IDs, start counts and checkpoint
contents distinguish all three starts.

## Requirements and limits

Foreground Service type and permissions are installed-manifest contracts, not
DEX-update configuration. Use the type appropriate to the real workload and obey
ordinary Android start eligibility, promotion deadlines and time limits. This
fixture's visible peer is an eligible caller; it does **not** bypass or test all
background-start restrictions. See [foreground launch rules](https://developer.android.com/develop/background-work/services/fgs/launch)
and [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

The synthetic file checkpoint is not a real long-running sync implementation.
There is no evidence here for every type, denied/while-in-use permissions, the
dataSync time quota, `onTimeout`, reboot, user force-stop, task removal, sticky
null-Intent restart, `stopSelfResult` ordering, concurrent starts or store-policy
approval. Restart timing is Android-controlled; this driver uses a bounded wait,
not an application guarantee of immediate restart. Target multiprocess Services,
other API/ABI versions, release shrinking and payload changes remain unvalidated.
