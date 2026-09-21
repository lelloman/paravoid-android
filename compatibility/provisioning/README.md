# APK-provisioned key experiment

This is a bounded fixture, **not the production updater or a conforming VPK
server**. It tests the distribution draft's public/key authorization and APK-based
credential replacement. No LelloStore, real account or publisher key is involved.

- `apk_record.py` adds a fixture-only signing-block entry to a copy of an APK.
  Existing signature entries and ZIP contents are preserved, with aligned block
  resizing. It refuses already-personalized input and never overwrites output.
- `server.py` is a Python-standard-library loopback server. Its `/probe/v1` routes
  serve unsigned metadata and a harmless artifact (maximum 1 MiB), not executable
  updates. Public and key access, conditional GET, HEAD and simple byte ranges
  share authorization. Configuration reload makes revocation testable without an
  exposed administration endpoint. Release immutability is enforced in memory for
  the server lifetime only. Do not deploy this development server publicly.
- `test_host.py` covers archive structure/bounds, public/private access, revocation
  including 304/HEAD/range requests, replacement keys and artifact immutability.

```sh
python3 -m unittest discover -s compatibility/provisioning -p test_host.py -v
```

The provisional JSON record contains `version: 1`, `applicationId`, `keyId`, and
`key`, capped at 4096 bytes, under signing-block ID `0x50564131`. These are **not
frozen wire-format choices**. Personalized credentials are readable/copyable and
are not protected by the developer's APK signature. Server acceptance proves
possession only, not a particular user/device or a trusted provisioning issuer.

Host structural tests use a synthetic signing block and do not prove cryptographic
validity. Actual APKs must pass `apksigner verify` before device installation.
Personalized APKs have different full-file hashes; do not reuse their originals'
v4 `.idsig` files. Device checks must use ordinary, non-incremental installation.
No custom block is inserted into a personal or published APK by these host tests.

## Emulator test

```sh
ANDROID_HOME=/path/to/android-sdk python3 compatibility/provisioning/check.py --serial emulator-5584
```

Start an API 30+ emulator first. The runner refuses physical devices. It requires
build-tools 36.0.0, `adb` on PATH and the repository's Gradle prerequisites. It
builds normal and shell variants, starts its own loopback server and uses `adb
reverse`. Only four `com.lelloman.paravoidcompat.provisioning.*` fixture packages
are installed/replaced; existing fixture data is retained. They remain installed
and force-stopped afterward. The server and its reverse mapping are cleaned up.

The 24 stages cover both packaging modes: absent/wrong-app records, invalid keys,
key A access and cold restart, revocation and restart, public access without a
record, an APK version update carrying key B with app data preserved, restart
with B, replay of the old APK with revoked A, and removal of the record by APK
replacement. Debug-only downgrade is deliberate for replay testing, not a
production update policy. Downloads exercise conditional GET and ranged transfer
with size/digest validation. Revocation leaves previously downloaded bytes intact.

The runner verifies original and personalized APKs with `apksigner`, checks their
signing certificates match, and rejects a control APK with damaged signed content.
The fixture explicitly enables v3 signing. The APK reader lives in the shell's
parent loader while the Activity remains in the embedded payload; both archive
inspection and device class-loader assertions check this boundary. This does
**not** prove empty-shell or pre-payload bootstrap behavior.

Random test credentials and personalized APKs are temporary, never checked in.
Device reports and Gradle logs go under ignored `build/`. Do not use real keys.
The standalone server accepts `--config /private/path/server.json --port 18765`:

```json
{"apps":{"example.app.paravoid":{"mode":"key","keys":{"test-key-id":"SHA256_OF_KEY"},"artifact":"/absolute/path/harmless.bin","release":"fixture-a"}}}
```

Use `"mode":"public"` for unauthenticated access. Replace the configuration
atomically to change authorization; an empty `keys` object revokes all access to
that keyed app. Routes are `/probe/v1/apps/{applicationId}/head` and
`/probe/v1/apps/{applicationId}/releases/{release}/artifact`. Key requests use
`X-Paravoid-Key-Id` and `Authorization: Bearer <key>`. No request headers are logged.

HTTP cleartext is exclusively a local fixture concession. Production delivery
still requires HTTPS, authenticated provisioning policy, signed VPK/head metadata,
compatibility validation and safe activation. The server's digest is a transfer
check, not independent authenticity. Nothing downloaded here is executed.
