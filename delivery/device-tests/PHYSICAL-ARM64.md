# Physical ARM64 release acceptance

Status: tooling prepared, **not executed on physical hardware**. On 2026-09-23
only the owned x86_64 emulators were connected. Emulator ARM64 translation is
not physical evidence. A dedicated authorized ARM64 device remains required.

The production signed-release HTTPS suite now has a separate opt-in. Its default
still accepts disposable emulators only. Build the non-debuggable empty/apkKey
release fixture using [HTTPS-RELEASE.md](HTTPS-RELEASE.md) first. Do not rerun CA
generation without rebuilding. Use throwaway fixture credentials only.

Before running, obtain the owner's permission to install and exercise **both**
fixture packages, manipulate their UI, and replace the shell credential APK.
Use an unlocked dedicated ARM64 device with API 30+, USB debugging enabled, one
Android user (no work profile), and sufficient space. Controls prefer the
package-manager-resolved manifest `UpdatesLauncher` alias, so the default shell
does not require launcher dynamic-shortcut support. Older or explicitly opted-out
shells still need a launcher supporting that shortcut. Do not interact with the device during execution.
Disconnect other devices if practical; an explicit serial is mandatory.

```sh
adb devices -l
ANDROID_HOME=/path/to/sdk python3 delivery/device-tests/https_release.py \
  --serial EXACT_AUTHORIZED_SERIAL --physical-arm64 \
  --allow-fixture-install com.lelloman.paravoidcompat.complete,com.lelloman.paravoidcompat.complete.paravoid \
  --server-port 18765
```

Read-only preflight rejects emulator markers, non-ARM64 primary ABI/kernel,
unsupported SDK, multiple users, any existing/retained fixture package data,
and an existing device-port 18765 reverse mapping. It repeats just before the
first mutation. These are accidental-target safeguards, not hardware attestation
or a defense against concurrent package installation. Consent does **not**
authorize overwriting an existing installation: it is always refused.

The test creates its own adb reverse mapping and removes it on completion/failure.
It never uninstalls packages in physical mode; successful or partial fixture
installs remain for inspection. It installs only the two fixed package IDs, then
uses replacement installs for its newly installed shell. It does not clear data,
reset the device, alter device trust, or log bearer credentials. UI automation
writes `/sdcard/paravoid-https.xml` and navigates the launcher; use only the
dedicated test device. Cleanup is a separate explicit owner decision, limited to
these two fixture packages (their test data will be deleted).

Record commit, artifact hashes, device model/build, API, primary ABI/kernel,
full command and PASS output. The suite validates signed release HTTPS, grant
personalization/replacement/revocation, TLS rejection, resumed payload download,
activation, and retained offline execution. It is not yet the entire physical
core matrix: add fixed-shell A-to-B/data retention, forward repair/quarantine,
leases/native loading and process-death cases with physical-safe runners before
closing the overall gate. It does not establish physical power-loss durability,
production backend/signing acceptance, or independent security review.

Host safety tests (no adb/device mutation):

```sh
python3 -m unittest discover -s delivery/device-tests -p test_physical_safety.py -v
```
