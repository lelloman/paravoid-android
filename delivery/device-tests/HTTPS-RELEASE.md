# Release-mode HTTPS acceptance

This fixture uses the production controller, HTTP client, verifier, lifecycle and
APK personalization CLI. Both APKs are release builds, signed with a throwaway
fixture certificate and **not debuggable**. The shell starts empty, requires an
APK-carried grant and disallows debug HTTP. No production credential is used.

## Reproduction

With JDK/keytool, Python cryptography, SDK 36 and the Gradle build dependencies:

```sh
python3 compatibility/complete-v1/prepare_https.py
gradle -p compatibility/complete-v1 assembleNormalRelease assembleParavoidAndroidRelease \
  packageParavoidAndroidReleaseParavoidVpk \
  -Pbootstrap=empty -Pauthentication=apkKey -Pgeneration=A -PpayloadVersion=1 -PhttpsAcceptance
ANDROID_HOME=/path/to/sdk python3 delivery/device-tests/https_release.py \
  --serial emulator-5586 --server-port 18765
ANDROID_HOME=/path/to/sdk python3 delivery/device-tests/https_release.py \
  --serial emulator-5584 --server-port 18766
```

Select only disposable API 30/36.1 emulators: the test uninstalls the fixture's
normal and shell packages. Distinct host ports permit concurrent runs. It uses
adb reverse and the real launcher shortcut, not a private runtime control API.
It removes its port mapping and shuts down its server even after failure.

`prepare_https.py` generates ignored TLS/signing material under the fixture's
build directory. It regenerates the CA, so rebuild the APK after running it.
Only this acceptance variant trusts the generated CA through Android's network
security configuration. It does not disable hostname/certificate verification,
modify emulator trust or change the normal downstream release policy. Never ship
the fixture CA, test keys or acceptance network policy in a real application.

## Assertions and limits

- Normal release payload UI works; run-as is refused for the release shell.
- The unpersonalized empty shell refuses updates without sending HTTP.
- CLI personalization preserves developer signatures and verifies its grant.
- An unrelated TLS certificate is refused before reaching the HTTP handler.
- Trusted HTTPS delivers signed metadata/VPK; a deliberately truncated response
  resumes with Range and the payload activates through the actual restart UI.
- Revocation refuses an explicit check without losing the active application.
  Immediate process restart runs the active app without new requests. This check
  is subject to foreground throttling; it does not independently demonstrate
  suppression after the six-hour throttle expires. Error UI is process-local.
- APK replacement with a new grant restores explicit checking using only the new
  key. Removing the grant fails closed, with no anonymous HTTP fallback, while
  the retained application still launches offline.

The server records credential indices and ranges, never bearer values. This is
local TLS/emulator acceptance, not public-infrastructure, physical ARM64,
independent security review or production signing-key acceptance. Cleartext public
debug tests elsewhere are not substituted for this release-mode test.

Passed 2026-09-23 on Restart30/API 30 and Restart36/API 36.1 x86_64, production
code at `52aea67`, with this fixture/harness. Build log:
`/tmp/paravoid-https-release-build.log`; full passing runs:
`/tmp/paravoid-https-release30.log` and `/tmp/paravoid-https-release36.log`.
Earlier harness runs incorrectly expected persisted error UI after process death;
the corrected assertions above verify transport and retained-app behavior.
