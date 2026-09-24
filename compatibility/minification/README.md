# Payload minification test app

This standalone app builds both a normal APK and a Paravoid APK. Its Paravoid
payload can be built with D8 or with the experimental R8 opt-in. It checks
reflection, `ServiceLoader`, `Parcelable`, a manifest provider, a manifest
receiver class, and the payload class loader. An unused class tests shrinking;
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
