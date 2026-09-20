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
  packaging mode; kill both scheduling processes and reboot once. Verify a changed
  kernel boot ID and increased Android boot count. Real BOOT_COMPLETED delivery
  loads each payload without a user Activity and reconstructs an elapsed-time
  alarm from the persisted wall deadline. Observe both post-boot deliveries.

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

Five device-free assertion tests guard active-alarm parsing against historical
records and other packages, stale run tokens, fixture errors, duplicate delivery,
and missing cold-entry/Parcelable/loader checks:

```sh
python3 -m unittest discover -s compatibility/os -p test_alarm_lifecycle_driver.py
```

## Requirements and boundaries

Declare `RECEIVE_BOOT_COMPLETED`, the recovery receiver and its filters in the
installed manifest, along with exact-alarm permission. The non-exported receiver
accepts genuine system broadcasts; there is no fixture-side synthetic dispatch.
The desired schedule uses credential-protected preferences: this tests ordinary
BOOT_COMPLETED after unlock, **not** Direct Boot/LOCKED_BOOT_COMPLETED or encrypted
storage before unlock. The app has been launched once before reboot and is not
force-stopped or in a restricted bucket as a test precondition.

Android documents that exact-alarm revocation removes pending exact alarms and
that the grant broadcast requires a fresh access check; see the
[AlarmManager permission-state contract](https://developer.android.com/reference/android/app/AlarmManager#ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).
The [Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby)
describes the forced-idle mechanism. This is one allow-while-idle delivery per
package, not a quota/frequency, natural Doze-entry, standby-bucket or OEM guarantee.
Settings UI, repeated grant/revoke races, multi-user boot, alarm-clock/repeating
APIs, clock/timezone changes, release/R8, payload updates and other ABIs remain
outside this probe.
