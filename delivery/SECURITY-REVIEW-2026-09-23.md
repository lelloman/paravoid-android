# Scoped internal security review, 2026-09-23

Baseline: `3b163a1`. This is an additional model-assisted code review, **not an
independent external security approval**, exhaustive audit or production signoff.
No phone, emulator, production account or remote publication was used.

## Finding and correction

`ApkGrantReader` bounded signing-block bytes to 16 MiB but accumulated every
distinct ID in a `HashSet`. A block of minimum-size unknown pairs can contain
roughly 1.4 million IDs; boxed keys and hash-table entries amplify allocation well
beyond the input size. Unknown carrier pairs are not protected by the developer's
v2 signature, so authenticated payload verification does not eliminate this
parser resource-exhaustion surface. No signature bypass was found; actual Android
OOM/crash severity was not measured.

The reader now stops at 1024 entries with `LIMIT_EXCEEDED`, before another ID is
allocated. This preserves normal v2/v3/grant/padding layouts (at most four entries)
and still permits a bounded number of unknown entries. `V1.md` documents the
compatibility limit. Tests accept exactly 1024, reject 1025, and reject pair
lengths 0, 3, signed-long maximum, high-bit-set and all-bits-set. Existing tests
also exercise actual signed v2/v3 APK personalization and APK-pinned readback.

## Inspected boundaries

The scoped source inspection covered `StrictJson`, `SignedMetadataVerifier`,
`CheckedZip`, `ApkGrantReader`, `apk_personalize.py`, `HttpTransport`, the credential
and admission handoffs in `DeliveryClient`, `AdmissionStore`, `SelectionJournal`
and `CancellationSignal`. Important defenses observed:

- JSON rejects duplicate keys, malformed Unicode, unsupported number forms and
  unknown schema fields. Signature roles are domain-separated and pinned keys
  are role-separated. Exact received body bytes are authenticated; canonical
  writer output is not confused with a requirement to canonicalize before verify.
- ZIP validation compares local/central metadata, rejects path traversal and
  overlapping/hidden records, and bounds decompressed output. This review did not
  fuzz Android's downstream resource/dex/native parsers or establish parser
  equivalence across every device implementation.
- HTTP refuses redirects and credentials outside the configured origin/path,
  validates conditional/range identity, and verifies downloaded hashes. Signed
  metadata still passes lifecycle admission; transport success is not execution.
- Admission rechecks installed credentials, revision and time at publication.
  Selection is protected by process leases; quarantine retry rejects a pending
  repair. Cancellation is checked before the deliberately non-cancellable staging
  handoff. These are observations of scoped paths, not a complete concurrency proof.

## Candidate signing-block ID survey

The candidate `0x50564132` differs from the following authoritative AOSP IDs
inspected on this date:

| Meaning | ID | Primary source |
| --- | --- | --- |
| v2 signature | `0x7109871a` | [AOSP v2 format](https://source.android.com/docs/security/features/apksigning/v2) |
| v3 signature | `0xf05368c0` | [AOSP verifier](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/util/apk/ApkSignatureSchemeV3Verifier.java) |
| v3.1 signature | `0x1b93ad61` | [AOSP introduction commit](https://android.googlesource.com/platform/frameworks/base/+/daadf0795e5771d3971f00742e1fd7e2c809ba6b%5E%21/) |
| source stamp v1 | `0x2b09189e` | [AOSP stamp source](https://android.googlesource.com/platform/frameworks/base/+/58fd58dea7f91fa9c42d0682a00f7169dd43a9b6/core/java/android/util/apk/SourceStampVerifier.java) |
| source stamp v2 | `0x6dff800d` | [AOSP constants history](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/gki13-boot-release%5E1..refs/heads/gki13-boot-release/) |

An exact web search for `"0x50564132"` did not identify another assignment. Neither
this search nor these AOSP constants establish global uniqueness, private vendor
usage or registration. The project owner/distributor must approve the private-ID
risk and relevant ecosystem survey before wire freeze. The ID was not changed.

The [AOSP v2 specification](https://source.android.com/docs/security/features/apksigning/v2)
ignores unrecognized pairs and excludes those pairs from developer-signed content;
this explains why grant verification needs its own pinned trust. APK grant bytes
remain extractable bearer credentials, not device attestation. Revocation blocks
future authorized delivery, not execution of an already usable offline payload.

The current CLI intentionally refuses source-stamped/v3.1/unknown layouts. It
rejects adjacent `.idsig`, does not regenerate v4, and cannot detect a detached
sidecar stored elsewhere. The distributor must never reuse an old sidecar after
personalization: [AOSP v4](https://source.android.com/docs/security/features/apksigning/v4)
describes its separate APK-content signature. No v4 acceptance is claimed.

## Validation and remaining approval

Run `bash delivery/test.sh` at this change. Evidence is recorded in
`/tmp/paravoid-security-delivery-tests.log`; the path is local and disposable.
This includes Java transport/controller/carrier/admission-handoff tests,
process-death/concurrent writer tests, Python reference-server tests and actual
v2/v3 APK personalization tests. Structural fake-APK tests do not substitute for
developer-signature verification; real signed-APK tests cover that separately.

Still required: independent reviewer against the final integrated commit,
distributor root/key custody and incident-response review, private-ID acceptance,
explicit v4 publishing policy, and final device/real-app acceptance. The broader
review packet remains `RELEASE-SECURITY-REVIEW.md`. No operational security gate
is closed merely because this scoped code finding was fixed.
