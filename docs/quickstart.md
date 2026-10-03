# Complete-profile quickstart

The complete profile builds an ordinary application APK, a standalone shell APK,
and a signed VPK. It is still under [release acceptance](../RELEASE-READINESS.md).
Use this local fixture before integrating another app.

## Build a local demo

Install JDK 21, Python 3 with `cryptography`, and Android SDK platform 36 with
build-tools 35.0.0 and 36.0.0. The wrapper selects Gradle 8.13; the plugin targets
AGP 8.13.2. Set `ANDROID_HOME` to your SDK directory.

From the repository root:

```sh
python3 -m pip install cryptography
python3 compatibility/complete-v1/prepare.py
./gradlew -p compatibility/complete-v1 assembleNormalDebug \
  assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk
```

Generated keys in `compatibility/complete-v1/build/keys/` are disposable fixture
keys. The default embedded bootstrap can start without downloading its initial
payload. Outputs are under `compatibility/complete-v1/build/outputs/`; the shell
and VPK are in `paravoid/paravoidAndroidDebug/`. Android API 30+ is required.
See the [fixture guide](../compatibility/complete-v1/README.md) for explicit
emulator selection, installed checks, empty bootstrap and update generations.

## Validate published dependencies locally

```sh
bash release-tests/local-publication.sh
```

This stages all nine coordinated modules and plugin markers in a fresh local
Maven directory, then builds the standalone [consumer](../release-tests/consumer)
without source substitution. It prints the retained repository location. The
local default version is `0.1.0-dev`; it is not a public release.
The consumer's `settings.gradle` and `build.gradle` show repository configuration,
plugin resolution, runtime dependency and signing/trust configuration together.

For your app, retain an ordinary Android manifest and components. Apply
`com.lelloman.paravoid`, use `packaging = 'complete'`, and configure your own
signing key and shell trust policy. Custom Application classes extend
`ParavoidAndroidApplication`; see the [v1 integration contract](../V1.md).
Plan shell compatibility and separate production credentials before distribution.
Optional [payload R8](../compatibility/minification/runtime-validation.md) remains
experimental; dependency consumer rules are automatic, app reflection rules and
saved-data compatibility still need validation.

## Run the checks

```sh
bash release-tests/regression.sh
bash release-tests/local-publication.sh
```

[GitHub CI](../.github/workflows/checks.yml) runs these host/build and local consumer
checks on pushes, pull requests and manual dispatch, retaining logs and test reports.
Installed-device, authenticated-app, physical ARM64, independent security and
remote publication evidence remain separate. Record final-candidate evidence with
[the acceptance audit](../release-tests/README.md) and the release checklist.
