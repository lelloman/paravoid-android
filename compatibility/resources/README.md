# Independent resource-pack experiment

This fixture tests the public Android `ResourcesLoader` / `ResourcesProvider`
APIs with a fixed app and two locally supplied **resource-only** packs. It is not
yet the core plugin's downstream resource-packaging implementation.

The fixture requires API 30+; the project-wide API 28 minimum is unchanged.
On 2026-09-20, both packaging modes passed A → B → A switching plus both rejection
checks on a fresh API 36.1 emulator. Builds and lint passed in both modes.

## Build and run

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/resources/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/resources/check.sh --device
```

Requirements: the repository's JDK/SDK setup, Build Tools 35.0.0 (`aapt2`), Python
3.9+, adb, and a dedicated unlocked emulator with enough free installation space.
The device script uninstalls/reinstalls and clears **only the two resource fixture
packages**, then removes them after successful checks. Their data is disposable.

## What is separate

- `resourcePacks` compiles `packs/common` plus A/B variants into
  `build/resource-probe/A.apk` and `B.apk`. These contain a resource table,
  compiled XML and assets, but no DEX. They are never installed as applications.
- The external package has ID `0x80`. The app compiles against A's generated R
  class. B uses A's emitted stable-ID ledger, adds a string that sorts before the
  existing names, and removes another string without reusing its reserved ID.
- The installed APK contains framework/dependency/bootstrap resources but none
  of the pack's feature strings, layout, drawable or asset. The app's executable
  code stays fixed, including its embedded Paravoid DEX payload in shell mode.
- The driver stages the selected resource APK in app-private storage using
  `run-as`, makes it read-only and changes a selector only between process starts.
- Fixture Application scaffolding attaches a shared loader to Application and
  Activity Resources before the Activity's `onCreate`. This scaffolding is not a
  proposed downstream integration requirement.

## Checks

For both normal and shell packaging, install once and cold-start A → B → A:

- Real Compose `stringResource` and an inflated Android XML TextView agree.
- XML background drawable and an external theme referencing external colors load.
- Application resource lookup and an Italian configuration context see the pack.
- `assets.open` returns the selected external asset.
- B's new resource appears; A's removed resource is unavailable in B rather than
  silently falling back to installed content.
- Shared numeric IDs remain stable. The installed APK's SHA-256 stays unchanged
  throughout all three starts, and each report comes from a new process.
- A tampered pack and a writable pack fail before publishing a successful report.

The installed test APK pins both pack hashes, and the loader hashes the same open
file descriptor used for loading. This permits a bounded local experiment, not
arbitrary external updates: new unpinned packs require rebuilding the test APK.
There is no network delivery, payload-signature protocol or security isolation
from other code running under the app's UID.

## Still to establish

- Automatically split an ordinary AGP application's own and library resources;
  this experiment hand-builds a separate resource namespace. Compose's dependency
  resources remain installed in the APK.
- Decide the resource package-ID and stable-ID ledger contract across releases,
  including resources referenced by the installed manifest.
- Couple independently updated code and resource versions atomically, with
  signatures, activation checks and rollback.
- Initialize external resources before providers if libraries require them there;
  this fixture attaches them during Application `onCreate`, after providers.
- API 30 device coverage, configuration changes/recreation, process-restored UI,
  native/JNI resources, arbitrary custom views and app-library themes. No private
  API fallback for Android 9/10 is implemented.

References: [Android resource loaders](https://developer.android.com/reference/android/content/res/loader/package-summary),
[ResourcesProvider](https://developer.android.com/reference/android/content/res/loader/ResourcesProvider).
