# OS integration probe

Pinned AndroidX Core 1.15.0 and Activity 1.10.1, compile/target SDK 36, min SDK 28,
Java 11. The target has one ordinary ComponentActivity and a manifest FileProvider.
The peer is a separate conventional APK/UID, with no Paravoid dependency or
storage permissions. Its Activity does not count against the target's one-Activity
rule. No instrumentation moves payload dependencies into the shell loader.

## Run

```sh
bash compatibility/os/check.sh
ANDROID_SERIAL=emulator-5584 bash compatibility/os/check.sh --device
```

Use a dedicated unlocked emulator. Python 3 and adb are required. The driver
installs and clears only these fixture packages, uses unique run tokens, and
force-stops them afterward. It leaves the APKs installed.
The notification driver requires API 33+ and an English, unlocked System UI.
It grants `POST_NOTIFICATIONS` to the fixture via adb; it does not test the
permission prompt. Only the named fixture packages are changed.

## Evidence and requirements

API 36.1 x86_64/debug: **40 assertions pass**, 20 per packaging mode:

- FileProvider content authority, narrow path rejection, defining-loader identity,
  and shell parent-loader isolation.
- Three cross-UID calls returning framework activity results: no grant (read
  denied), read-only Intent/ClipData grant (exact Unicode contents), and a new
  no-grant call after explicit revocation (read denied). Writes are denied in all
  three calls; each result verifies its run token and the peer's distinct UID.
- Cold provider entry: after an explicit read-only package URI grant, the driver
  backgrounds the target and kills its verified PID (not force-stop). A standalone
  peer read starts the target again, without launching the target Activity. Exact
  file contents, denied writes, a new Application PID, and an unchanged Activity
  entry PID are checked. This also verifies transformed Application initialization
  through a provider-only process entry.

The manifest must retain the provider, `${applicationId}`-based authority,
`android:exported="false"`, `android:grantUriPermissions="true"`, and paths metadata.
Use narrow paths, not the entire private filesystem. The paths XML and file
provider declarations currently belong to the installed APK; they cannot be
changed through a DEX-only update. Ordinary Android URI grant rules still apply.
No special Paravoid adapter or production changes were needed for this scenario.
The cold scenario uses `grantUriPermission`, not a persistable grant or a fresh
Intent grant; that explicit grant is not tied to the original Activity lifetime.

## Notification scenario

API 36.1/debug: both packaging modes pass. The initial reproducer passed normally
but failed on shell cold Activity entry with `BadParcelableException` for
`ProbeParcel`. Android's `ActivityThread` overwrites the launch Intent extras
loader after `AppComponentFactory` returns. The existing pre-`onCreate` hook now
repairs both the launch Intent and saved-state Bundle, including null-state cold
entries. The broadcast action already worked. The driver remains a regression
test for this failure; the fixture never sets an extras loader manually.

`notifications-device-check.py` posts a real channel notification, checks the
published content/action tokens, and hands the published broadcast action token
to the separate peer. The target's verified PID is killed before the peer sends
the token with attempted extra injection. The receiver is non-exported; its
PendingIntent is explicit and immutable. A payload-only Parcelable must survive
the cold receiver entry, with the original token and no injected extras.
The driver kills that new process too, opens the notification shade and taps the
notification title to exercise cold Activity delivery and Parcelable decoding.
Application/Activity/receiver PIDs distinguish each entry path.

The peer declares narrow `<queries>` for both fixture target packages so it can
inspect the PendingIntent creator identity. Without these, the API 36.1 control
returned a null package and UID -1. Notification posting is asynchronous; the
fixture waits for its published notification instead of assuming immediate
visibility in `getActiveNotifications()`.

Stable installed Activity/receiver component names, the notification permission,
and an enabled channel are required. Content delivery targets the real user
Activity directly, without a receiver/service notification trampoline. See
[Android's PendingIntent reference](https://developer.android.com/reference/android/app/PendingIntent)
and [notification navigation guidance](https://developer.android.com/develop/ui/views/notifications/navigation).
The content scenario deliberately creates a fresh task; existing-task
`onNewIntent`, back-stack policies, mutable/RemoteInput tokens, services,
notification action-button UI, reboot and payload updates are not covered.

## AndroidX activity-result scenario

API 36.1/debug: all **8 scenarios pass** (two packaging modes × success/cancel ×
alive/process-death). No additional runtime adapter was needed beyond the existing
Activity loader/saved-state hooks and the launch-Intent repair described above.

`results-device-check.py` exercises `StartActivityForResult` with a separate-UID
peer holding the result. It tests success (exact Unicode data/run token/peer UID)
and cancellation (no data), each with an alive caller and after killing its
verified stopped PID. The peer remains alive and returns through its UI button;
the driver never relaunches the target to retrieve the result.

The callback verifies the registry's delivery, caller recreation, a payload-only
Parcelable restored from saved state, AndroidX's payload defining loader, exactly
one callback and exactly one peer launch. The driver checks Application and
Activity PIDs and that the callback/launch counts remain unchanged after delivery.
This complements the original framework callback sharing tests, which still run.

As in ordinary Android apps, registrations must be unconditional and ordered
consistently on every recreation, before the Activity starts. Launch only for a
new request; save any extra callback state separately. This fixture registers in
field initialization and saves its checkpoint in `onSaveInstanceState`; it does
not recover callback state from preferences. Preferences only report observations.
See [Android's activity-result requirements](https://developer.android.com/training/basics/intents/result).

## Remaining coverage

This does not yet prove revocation while a peer still holds an open descriptor,
persistable grants or chooser flows. The last no-grant call proves access
is denied after revocation, not that task completion alone would retain a grant.
Runtime permissions, App Links, other Android
versions and release builds remain separate coverage. Additional result contracts
(camera, documents, permissions), Fragment-owned registries, multiple in-flight
requests, peer process death, and pending results across payload-version changes
are not covered by the `StartActivityForResult` scenarios.
