# AlarmManager cold-delivery probe

Build the OS fixture, then run on a dedicated unlocked API 31+ emulator:

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/os/check.sh
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/alarms-device-check.py
```

The standalone driver clears only the OS fixture/peer data, changes the fixture's
`SCHEDULE_EXACT_ALARM` app-op, and restores that app-op to default afterward. It
does not exercise the special-access Settings UI. It is deliberately not included
in the shared API 28/29 runner, where this permission contract does not apply.

Both normal and shell run identical `AlarmManager.setExact` calls using
`ELAPSED_REALTIME_WAKEUP` and explicit immutable broadcast PendingIntents targeting
a manifest-declared, non-exported payload receiver:

- Denied access: `canScheduleExactAlarms()` is false and scheduling throws
  `SecurityException`.
- Allowed access: enqueue an alarm, observe OS registration, background the
  Activity, then kill its verified PID (not force-stop). The OS starts a new
  process without entering the user Activity. A payload-only Parcelable with
  Unicode data decodes without fixture-side classloader repair.
- Replacement: schedule twice with the same PendingIntent identity, updated
  extras and a later deadline. Assert token identity, exactly one observed
  delivery, updated payload and no delivery before the replacement deadline.
- Cancellation: cancel a scheduled token. A later real sentinel alarm must arrive;
  after three additional seconds the cancelled receiver must remain unobserved.

PendingIntent identity uses an explicit component and run/kind URI, not extras.
The receiver records timestamps, PID, payload-classloader checks and per-kind
delivery counts. Preferences are observations only, not a scheduling mechanism.

## Verification evidence

API 36.1 x86_64/debug: both packaging modes pass the denied-access case and the
allowed cold-delivery/replacement/cancellation case (four PASS reports). Both
variant lint tasks and the existing cold notification action/Activity regression
pass. No production runtime or plugin changes were required.
Other API levels and ABIs have not been verified for this probe.

## Requirements and limits

Declare the receiver and any required permission in the installed manifest; shell
payloads cannot add components or permissions dynamically. Exact-alarm access is
an OS/user policy, not something Paravoid bypasses. Production callers should
check access and handle denial rather than copy this intentional exception probe.
See Android's [alarm scheduling guide](https://developer.android.com/develop/background-work/services/alarms)
and [AlarmManager contract](https://developer.android.com/reference/android/app/AlarmManager).

This is an awake-emulator functional test, not an exact wall-clock latency or
infinite non-delivery guarantee. It does not cover inexact/repeating alarms, RTC
clock changes, OnAlarmListener, Doze/standby quotas, allow-while-idle/alarm-clock
APIs, revocation with pending alarms, permission UI, reboot/boot rescheduling,
force-stop semantics, payload updates, OEM devices, release/R8 or other ABIs.
