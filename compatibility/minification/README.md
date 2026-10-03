# Payload minification test app

This standalone app builds both a normal APK and a Paravoid APK. Its Paravoid
payload can be built with D8 or with the experimental R8 opt-in. It checks
reflection, `ServiceLoader`, `Parcelable`, a manifest provider, a manifest
receiver class, dependency consumer rules, saved serialized settings, and the payload class loader. An unused class tests shrinking;
an explicitly retained class tests obfuscation.

Run the build comparison from the repository root:

```sh
bash compatibility/minification/check.sh
```

Set `ANDROID_HOME` or create `compatibility/minification/local.properties` with
your `sdk.dir` first. The script assembles the normal and unshrunk Paravoid APKs,
then the minified Paravoid APK. It checks that R8 removes `UnusedProbe`, renames
`RenameProbe`, keeps runtime entry points, writes a mapping file, and reduces
the payload DEX size. The final APKs are in `build/outputs/apk` here.

For a device check, install the **minified Paravoid APK** from
`build/outputs/apk/paravoidAndroid/debug` and launch its icon. The screen
should say `PASS`. You can also install the normal APK from
`build/outputs/apk/normal/debug` as a control. They have distinct application
IDs, so both can stay installed. The build comparison alone does not establish
runtime behavior.

The automated device check requires an explicit emulator serial and AVD name
and installs only these fixture packages:

```sh
python3 compatibility/minification/device-check.py --serial emulator-5584 --avd ParavoidR8
```

Both APKs passed this device check on a fresh API 36.1 x86_64 emulator. The
Paravoid APK used the R8 payload and kept reflection, Java service discovery,
parceling, provider calls, and the payload class loader working at runtime.

Keep rules in `payload-rules.pro` cover the names read by reflection and the
`META-INF/services` descriptor. The plugin generates rules for manifest
components and the payload Application.

See [runtime validation](runtime-validation.md) for dependency keep rules and
upgrade tests with saved preferences. The fixture checks transitive rule consumption and retained serialized data;
real DataStore/Hilt/Navigation application workflows still need their own checks.

## Upgrade with existing saved data

Build and retain unminified seeds before building the minified replacement:

```sh
./gradlew -p compatibility/minification assembleNormalDebug assembleParavoidAndroidDebug \
  -PminifyPayload=false -PprobeVersion=1
mkdir -p compatibility/minification/build/upgrade-seed
cp compatibility/minification/build/outputs/apk/normal/debug/minification-compatibility-normal-debug.apk \
  compatibility/minification/build/upgrade-seed/normal.apk
cp compatibility/minification/build/outputs/apk/paravoidAndroid/debug/minification-compatibility-paravoidAndroid-debug.apk \
  compatibility/minification/build/upgrade-seed/shell.apk
./gradlew -p compatibility/minification assembleNormalDebug assembleParavoidAndroidDebug \
  -PminifyPayload=true -PprobeVersion=2
python3 compatibility/minification/device-check.py --serial emulator-SERIAL --avd EXPECTED_AVD \
  --seed-directory compatibility/minification/build/upgrade-seed
```

The runner resets only the two disposable fixture packages before seeding. It
saves a non-default value (73) and a random per-seed nonce, installs the replacement without clearing data,
checks dependency classes reached only through reflection, and verifies identical
serialized bytes (including the nonce, so recreating defaults cannot pass) after replacement and another cold start. The Android library's
consumer rules keep the persisted class/field names; the JAR supplies its own
reflection rules. App-supplied rules do not mention either dependency class.

## Current validation — 2026-10-03

Four focused plugin regressions pass: deterministic/deduplicated rules,
version-targeted rule selection, transitive AAR/JAR rules with incremental
invalidation and opt-out cleanup, and manifest entry/metadata retention.

Unminified v1 seeds and minified v2 replacements built successfully. The actual
payload tool was SDK R8 `8.6.2-dev` (recorded in `payload-consumer-rules.pro`). On
`ParavoidReview36` / `emulator-5596`, API 36.1 x86_64, both normal and shell modes
passed seed startup, replacement, existing settings reads, another cold start,
and exact serialized-byte/nonce preservation. Logs:
`/tmp/paravoid-item4-plugin.log`, `/tmp/paravoid-item4-seed-build.log`,
`/tmp/paravoid-item4-target-build.log`, `/tmp/paravoid-item4-upgrade.log`.
This does not close complete-profile shrinking or real-app DataStore acceptance.
