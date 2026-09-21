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
