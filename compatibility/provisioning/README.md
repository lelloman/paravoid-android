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

HTTP cleartext is exclusively a local fixture concession. Production delivery
still requires HTTPS, authenticated provisioning policy, signed VPK/head metadata,
compatibility validation and safe activation. The server's digest is a transfer
check, not independent authenticity. Nothing downloaded here is executed.
