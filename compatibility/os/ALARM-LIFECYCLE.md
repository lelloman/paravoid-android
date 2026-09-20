# Alarm lifecycle: access revocation, idle and reboot

This extends [the basic alarm probe](ALARMS.md). Build with
`bash compatibility/os/check.sh`, then use a **disposable, dedicated API 31+
emulator**:

```sh
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/alarm-lifecycle-device-check.py revoke
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/alarm-lifecycle-device-check.py idle
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/alarm-lifecycle-device-check.py reboot
```

These commands clear the fixture packages. The idle case changes device-wide
battery simulation/idle state and restores it afterward; the reboot case actually
reboots the emulator. Do not run alongside another device test. Interrupted runs
may require `adb -s SERIAL shell dumpsys deviceidle unforce` and
`adb -s SERIAL shell dumpsys battery reset`. Fixture app-ops are restored to their
default, not to arbitrary pre-existing customization. These drivers are not in
the API 28/29 or general OS runner.

## Contracts under test

- **Revocation:** schedule a real future exact alarm, revoke the fixture app-op,
  observe the OS kill its process and remove its active alarm. Regrant access and
  receive the real protected permission-state broadcast, not a synthetic adb
  broadcast or Activity launch. The payload receiver checks current permission,
  reads the saved desired schedule and schedules a fresh alarm. Observe successful
  recovery and absence of the original delivery past its deadline.
- **Deep idle:** schedule ordinary exact and exact-allow-while-idle alarms, kill
  the scheduling process, unplug the simulated battery and force deep idle. The
  allow-while-idle alarm must cold-start the payload receiver while PowerManager
  still reports idle. The ordinary alarm must remain pending and unobserved until
  idle is released, then deliver, with the device subsequently confirmed ACTIVE.
  No whitelist or quota configuration is changed.
- **Reboot:** persist a desired wall-clock due time and register an alarm in each
  packaging mode; kill both scheduling processes and perform a framework-managed
  reboot (`svc power reboot`). Verify a changed
  kernel boot ID and increased Android boot count. Real BOOT_COMPLETED delivery
  loads each payload without a user Activity and reconstructs an elapsed-time
  alarm from the persisted wall deadline. Observe both post-boot deliveries.
  Assert that both packages are launched/not stopped before and after reboot.

The fixture deliberately owns persistence and rescheduling: Paravoid does not
provide an alarm database. The reboot example imposes a ten-second minimum delay
after recovery, including overdue work; this is a test policy, not general
calendar semantics. Permission recovery schedules ten seconds after regrant.
Application-local Activity state and Android boot count avoid treating recycled
PIDs as proof of cold startup. Delivered extras include a payload-only Parcelable.

## Harness findings

In the normal control, the deferred ordinary alarm arrived during idle exit while
its receiver still observed `PowerManager.isDeviceIdleMode() == true`. Alarm
dispatch and idle-state updates need not become visible together. Requiring that
flag to be false inside the callback was too strict. The driver instead proves
the alarm is pending and absent while forced idle remains active, timestamps the
release command, requires delivery after that timestamp, and confirms ACTIVE.
It still requires the allow-while-idle callback to observe idle=true.

An initial immediate `adb reboot` run delivered BOOT_COMPLETED to the normal
package but not the shell package, with no shell startup/crash. Follow-up diagnosis
reproduced lost package state in **both** modes, independently of payload loading:

- Repeating the original setup in normal-first and shell-first order delivered
  both boot broadcasts and alarms. The failure is timing-sensitive, not a
  deterministic shell receiver failure.
- Merely clearing and waiting was not a reliable stopped-state precondition:
  granting exact-alarm access could already start the manifest receiver.
- The controlled case granted access, force-stopped both fixtures **before** the
  test launches, waited 15 seconds for that state to settle, then launched and
  killed their exact PIDs. Both packages reported `stopped=false` immediately
  before reboot. Immediate `adb reboot` restored `stopped=true` for **both**.
  Their saved desired schedules remained intact, but boot delivery never entered
  either Application. All observations were captured before cleanup.
- Repeating that controlled setup but waiting 15 seconds **after launch** before
  `adb reboot` preserved `stopped=false` and delivered the real boot broadcast and
  rescheduled alarm in both modes, with no user Activity in the new boot.
- The same controlled setup followed immediately by `svc power reboot` also
  preserved the flags and delivered both callbacks and alarms, without the extra
  post-launch wait. These diagnosis runs used API 36.1 x86_64 build
  `BE4B.251210.005/14574095`; no production code or manifest was changed.

The Android 16 QPR2 [PackageManagerService implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageManagerService.java)
queues stopped-state persistence with a ten-second delay; its framework shutdown
path flushes pending settings. The [intent resolver](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/IntentResolver.java)
filters stopped targets, using the [component resolver's package-state check](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/resolution/ComponentResolver.java).
This establishes an Android package-state rollback mechanism that can explain
the original asymmetry; the original run lacked pre-cleanup package-state captures,
so its exact persisted flags cannot be retrospectively proven.

The regression driver uses framework-managed reboot and checks package flags on
both sides. It tolerates adb transport closure during `svc` and verifies the
changed boot ID. No production Paravoid fix is indicated by this reproduction.
Abrupt reboot/power-loss durability is not covered by the passing reboot contract.

Five device-free assertion tests guard active-alarm parsing against historical
records and other packages, stale run tokens, fixture errors, duplicate delivery,
and missing cold-entry/Parcelable/loader checks:

```sh
python3 -m unittest discover -s compatibility/os -p test_alarm_lifecycle_driver.py
```

## Verification evidence

API 36.1 x86_64/debug: revocation/recovery, forced deep-idle delivery/deferral and
framework-managed reboot/rescheduling each pass in normal and shell packaging
(six PASS reports). Both variant lint tasks, the five host assertion-guard
tests and all four basic-alarm regression cases pass. No production runtime or Gradle plugin change was needed. Other OS
versions/ABIs are not implied by this evidence.

## Requirements and boundaries

Declare `RECEIVE_BOOT_COMPLETED`, the recovery receiver and its filters in the
installed manifest, along with exact-alarm permission. The non-exported receiver
accepts genuine system broadcasts; there is no fixture-side synthetic dispatch.
The desired schedule uses credential-protected preferences: this tests ordinary
BOOT_COMPLETED after unlock, **not** Direct Boot/LOCKED_BOOT_COMPLETED or encrypted
storage before unlock. The app has been launched once before reboot and is not
force-stopped as a test precondition. The driver does not set standby buckets;
restricted-bucket boot policies are not separately exercised.

Android documents that exact-alarm revocation removes pending exact alarms and
that the grant broadcast requires a fresh access check; see the
[AlarmManager permission-state contract](https://developer.android.com/reference/android/app/AlarmManager#ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).
The [Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby)
describes the forced-idle mechanism. This is one allow-while-idle delivery per
package, not a quota/frequency, natural Doze-entry, standby-bucket or OEM guarantee.
Settings UI, repeated grant/revoke races, multi-user boot, alarm-clock/repeating
APIs, clock/timezone changes, release/R8, payload updates and other ABIs remain
outside this probe.
