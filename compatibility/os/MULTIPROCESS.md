# Named worker process probe

An ordinary Service declared with `android:process=":worker"`, using the OS
fixture's existing AIDL/Parcelable contract. There is still only one application
Activity. No production loader or plugin adaptation is introduced by this probe.

```sh
bash compatibility/os/check.sh
python3 -m unittest discover -s compatibility/os -p 'test_multiprocess_driver.py'
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/multiprocess-device-check.py
```

Use a dedicated unlocked emulator on primary user 0, Python 3.8+ and adb. The driver installs and
clears only the OS fixture targets and peer. The full OS `check.sh --device` suite
also includes this driver.

## Verification evidence

API 36.1 x86_64/debug: all **12 connection stages pass** (two packaging modes ×
two clients × three connections), including destruction after final unbind and
dead-token/disconnection checks. Normal, shell and peer builds/lint pass; seven
host tests exercise the multiprocess driver's assertions and receiver restoration
after failures.
No production runtime or plugin fix was required. Other Android versions and
ABIs have not been run for this new scenario.

The final combined OS device suite also passes: 40 sharing/provider assertions,
both notification modes, eight Activity-result scenarios, existing default-process
Binder recovery, all 12 worker connections, and both foreground-service modes.
All 12 OS host tests pass (seven worker-driver tests plus five alarm-driver tests).

The first combined-suite run exposed a fixture interaction in **normal** packaging:
ActivityManager started `AlarmLifecycleReceiver` in the main process while the
worker-only case was running. Android 15+ can deliver `BOOT_COMPLETED` when an app
leaves stopped state, not only at physical boot ([platform behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)).
The driver therefore temporarily disables this unrelated alarm-test receiver and
restores its manifest-default state after stopping the fixture. These operations
use `run-as` with the debuggable fixture's own UID; direct shell component-state
changes are rejected on the tested image. The no-main-process
assertion remains strict; this does not require downstream apps to disable their
receivers, nor imply that a remote Service prevents other components from starting.

## Scenarios

Each normal/shell target is exercised with two clients:

- An external peer binds to the cold worker while the target's main process stays
  absent. The existing flavor-specific signature permission protects the endpoint.
- The target Activity binds to its own worker. Both app processes coexist; each
  has its own Application initialization and, in shell mode, payload class loader.

Each client checks the first connection, explicit unbind/rebind, then automatic
reconnection after killing only the verified worker PID. The client PID must stay
unchanged. Final unbind must destroy the recreated Service. Application startup
must happen once per process, not once per Service binding; a restarted worker
must have a new Application identity. The Activity-created static flag must remain
false in the worker even when the Activity exists in the main process.

All three connections repeat real Binder proxy checks: typed and nested-Bundle
custom Parcelables, explicit Bundle loaders and the unprepared negative control,
Unicode, callbacks, nullable lists, remote exceptions and caller UID. Generated
client/server classes must use their respective application loaders. The shell
parent must not see the server's generated contract. DeathRecipient, stale-token
failure and disconnection must precede successful recovery observations.

## Requirements and boundaries

- The process declaration must already exist in the installed manifest; changing
  code alone cannot introduce new manifest components/process declarations.
- Application initialization runs separately in each process. In-memory singletons
  and static state are not shared; downstream initialization must account for the
  process it runs in. See Android's [Service process declaration](https://developer.android.com/guide/topics/manifest/service-element#proc).
- This is a regular same-UID worker, **not** `isolatedProcess`. Its exported test
  endpoint is signature-protected, not an example of an unrestricted service.
- Report preferences have one writer process per file (`os-probe` / `os-worker`);
  the host reads XML through adb. This is not cross-process SharedPreferences
  synchronization support or a suggested production IPC mechanism.
- Independent payload delivery/version agreement, shared database concurrency,
  multiprocess WorkManager, isolated services, client death, remote-process
  providers/receivers, startup races, other OS/ABI versions and release shrinking
  are not established by this scenario. Existing Binder wire-contract and explicit
  Bundle-loader requirements still apply; see [BINDER.md](BINDER.md).
