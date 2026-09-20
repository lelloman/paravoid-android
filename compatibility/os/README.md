# OS integration probe

Pinned AndroidX Core 1.15.0, compile/target SDK 36, min SDK 28, Java 11.
The target has one ordinary framework Activity and a manifest FileProvider.
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

## Limits

This does not yet prove revocation while a peer still holds an open descriptor,
persistable grants, chooser flows, AndroidX Activity Result
contracts or results across process death. The last no-grant call proves access
is denied after revocation, not that task completion alone would retain a grant.
Notifications/PendingIntent, runtime permissions, App Links, other Android
versions and release builds remain separate coverage. This fixture intentionally
uses the framework activity-result callback, not AndroidX's registry.
