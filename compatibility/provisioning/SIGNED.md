# Signed provisioning/discovery experiment

This extends the original unsigned experiment; it does not freeze protocol v1 or
produce VPKs. Artifacts are still harmless bytes, never dynamically executed.

`signed_profile.py` uses Python `cryptography` (tested with 41.0.7) to sign exact
ASCII payload bytes using RSA-2048 / PKCS#1 v1.5 / SHA-256. Android independently
verifies with JCA `SHA256withRSA`. This is an experimental interoperability choice,
not the final signing/rotation/delegation policy. See the official
[Python signing API](https://cryptography.io/en/stable/hazmat/primitives/asymmetric/rsa/)
and [Android signature API](https://developer.android.com/reference/java/security/Signature).

Envelopes have exactly five LF-separated elements: `PARAVOID-PROBE-1`, kind
(`grant` or `head`), standard padded base64 payload, base64 signature, and an empty
terminal element. Signatures cover the first two lines (including LFs) followed
by decoded payload bytes. The payload is ordered `field=value` lines, with a final
LF; unknown/repeated/reordered fields, non-ASCII values, noncanonical base64 and
envelopes larger than 4096 bytes are rejected. Field order is in `FIELDS` in the
Python module and independently in the Java verifier. Separate trust roots and
signature domains distinguish grants from heads.

The server optionally reads a bounded pre-signed envelope from `signedHead` in an
app's config. It holds no signing private keys and deliberately does not validate
the envelope: an untrusted server must not replace client-side verification.
Existing authorization still precedes conditional, HEAD and download responses.

```sh
python3 -m unittest discover -s compatibility/provisioning -p 'test_*.py' -v
```

## Android experiment

```sh
ANDROID_HOME=/path/to/sdk python3 compatibility/provisioning/check_signed.py --serial emulator-5584
```

The same SDK/emulator prerequisites and four-package scope as [the original
runner](README.md#emulator-test) apply. Python additionally needs `cryptography`.
The runner generates new independent issuer and publication keys per invocation;
private keys stay in host memory. Public DER keys and trusted policy are packaged
as APK assets with the experimental `probeTrustDir` build property. No request or
provisioning record can replace those roots. Temporary grants/APKs are deleted;
the installed, stopped fixture APKs and ignored build artifacts retain only test
credentials/public trust material. This is not a production plugin DSL.

Grants bind application, fixed fixture-service audience, credential ID/key and
issue/expiry times. Verification precedes **any network request**; tests assert
zero requests for rejected grants. Grant lifetime is capped at one day. Public
mode requires no grant but applies identical discovery/artifact verification.

Heads bind application, a fixture contract ID, channel, revision, release ID,
payload version, issue/expiry times, size and SHA-256. Lifetime is capped at one
hour; future issue times allow at most 60 seconds of clock skew. Integers are
canonical nonnegative decimal values at most 2^53-1. Download size is capped at
1 MiB. The client derives a same-origin path from a restricted release ID rather
than accepting arbitrary download URLs. Redirects are disabled; a second server
acts as a cross-origin trap and must receive no requests.

Atomic private state records the verified revision, version, release identity,
descriptor digest, ETag and exact signed envelope **before downloading**. Older
revisions/versions, changed metadata at the same revision and changed release
identity/digest at the same version are rejected. Cached descriptors are verified
again on every 304, including expiry checks. A 304 without a matching cached
descriptor is rejected. State is scoped to application/contract/channel/publisher;
new generated trust roots distinguish independent test runs, while force-stop and
restart within a run retain the high-water mark. Corrupt state is not silently reset.

Ranged transfers validate Content-Range/ETag, signed length and digest. A complete
200 response replaces the requested partial transfer. Only fully verified harmless
bytes replace `signed-artifact.bin`, via `AtomicFile`. Negative tests assert the
previous file remains unchanged. This is data-file staging, **not code activation**.

Coverage in normal and shell packaging, in both public and key modes:

- Valid signed download and cold restart/304 cache reuse.
- Untrusted publisher, issuer/publisher role confusion and damaged signatures.
- Wrong application/contract/channel, expired/future heads, revision replay,
  same-revision equivocation, payload downgrade and version-identity reuse.
- Oversized signed artifacts, corrupt transfer, wrong ranges, full-200 fallback,
  cross-origin redirect refusal and a newer valid release followed by old-release replay.
- A short-lived accepted descriptor subsequently expires: 304 must not renew it.
- Keyed variants additionally reject missing/unsigned/wrong-issuer/wrong-app/
  wrong-audience/expired/future grants before network access, and reject cached
  discovery after server-side credential revocation.

Results are written to ignored `build/signed-api{SDK}.json`. Each scenario starts
a new app process; replay/cache evidence is not merely an in-memory check.
Time-sensitive vectors use the emulator's clock, which can lag the host, and the
expiry test waits for that clock to cross the deadline. This makes the assertion
deterministic; it does not solve hostile or incorrect clocks in production.

## Still not proven

These are `/probe/v1` routes and only the `available` outcome. There is no complete
VPK archive/inventory verifier, capability negotiation, generated shell-contract
validation, delegated signing/rotation/revocation, executable activation, empty
bootstrap or production integration. Grant audience is a fixture-service label;
the transport endpoint is fixed to loopback with a runner-selected port. Cleartext
and test-Intent configuration must not be copied into production.

Freshness trusts the device wall clock. Reinstall/data loss/backup rollback and
clock manipulation are not solved by local counters. State and harmless artifact
writes are individually atomic, not a cross-process transaction; crash injection,
concurrent processes, low storage, interrupted persistent resume, TLS/CDNs and
physical ARM64/release/R8 paths remain untested. Grant replacement through APK
updates remains covered by the original unsigned-record suite, not this new
signed-record runner. This profile is evidence for specific design requirements,
not a security audit or a claim of production protocol conformance.
