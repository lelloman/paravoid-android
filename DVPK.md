# Delta VPK delivery, version 1

This document specifies the optional delta transport implemented by the complete
shell. The complete VPK format and its developer signature remain specified in
[V1.md](V1.md). A DVPK reconstructs that exact archive; it is never loaded, merged
into a running generation, or admitted as a release itself.

## APK-only publishing and LelloStore responsibilities

The app developer builds and uploads a **complete-profile shell APK with an
embedded payload**. The Paravoid plugin creates and signs the full VPK during
that build. The publisher uploads only the APK; it uploads neither a separate VPK
nor a DVPK. LelloStore performs these steps:

1. Verify the uploaded APK's developer signatures and authorized app identity.
   Read its installed policy from `assets/paravoid/shell-policy.json` and extract
   `assets/paravoid/payload.vpk`. Reject duplicate asset names, malformed archives,
   oversized inputs, or missing payloads. Read these assets from the original
   verified APK, before any optional grant personalization.
2. Verify the extracted VPK against that policy using the complete VPK rules:
   developer release signature, inventory, ledger, installed contract and app
   identity. Check the full supported ABI inventory, rather than accepting a
   target solely because it works on one backend host. Retain the original APK
   and **exact original VPK bytes**, indexed by SHA-256 and byte size.
3. Compare the target's shell contract with eligible installed shells. A changed
   contract requires an APK installation; a delta cannot repair incompatible
   shell declarations, trust, resources or runtime code. APK versionCode alone
   does not determine payload compatibility. Normal APKs and empty-bootstrap APKs
   do not contain a signed payload and cannot publish payload updates through
   this APK-only flow.
4. For useful archived bases with the same installed contract, generate direct
   `base -> target` DVPKs. Reconstruct each candidate and require byte-for-byte
   equality with the archived target before publication. Record exact base,
   patch and target hashes and sizes. Generation can happen asynchronously; the
   full VPK is publishable immediately without a delta.
5. Always retain and serve the full target VPK. Publish optional patch descriptors
   in the authenticated discovery metadata, signed with the existing discovery
   key. The target's developer signature remains untouched. LelloStore needs no
   developer release private key to extract a VPK or create a DVPK.

This repository implements the shell, wire contract and a reference encoder;
production LelloStore ingestion, archive retention and delta generation are the
distributor's implementation. Stores may generate deltas only for popular bases
or recent releases. There is no requirement to generate one for every release.
Clients several versions behind can use a direct delta if available, otherwise
they download the full latest compatible VPK. Delta chains are unsupported.

## Discovery negotiation

The built-in HTTP v1 client sends these headers on head requests:

```http
X-Paravoid-Dvpk: bsdiff-deflate-v1
X-Paravoid-Base-Sha256: <lowercase SHA-256 of selected full VPK>
```

The base header is omitted when there is no selected archive identity. It is a
hint, not proof of entitlement or integrity. Keep existing request scope,
credentials, temporal validity, signed head revision and replay rules unchanged.

An old server can ignore these headers and return its existing full-only head.
Servers **must omit `deltas` for clients that did not negotiate support**: older
shells reject unknown signed fields. HTTP caches must vary by both headers and
the existing scope/authentication rules; include
`Vary: X-Paravoid-Dvpk, X-Paravoid-Base-Sha256` where responses depend on them.
An ETag/304 must refer to the same representation and its existing freshness
deadline. Changing signed descriptors requires a fresh head revision; never
reuse a revision with different signed bytes.

An AVAILABLE head's existing signed `release` object may contain `deltas`:

```json
{
  "releaseId": "release-20",
  "payloadVersion": 20,
  "manifestSha256": "<target signed release.json SHA-256>",
  "archiveSha256": "<target complete VPK SHA-256>",
  "archiveSize": 5650000,
  "deltas": [
    {
      "algorithm": "bsdiff-deflate-v1",
      "baseArchiveSha256": "<exact base complete VPK SHA-256>",
      "baseArchiveSize": 5500000,
      "patchSha256": "<DVPK SHA-256>",
      "patchSize": 850000
    }
  ]
}
```

Placeholders above stand for 64 lowercase hex characters. The entire release
object is covered by the existing signed metadata envelope. A patch has no
separate signature: its descriptor authenticates its bytes and base; the parent
release authenticates the target. Unknown fields, malformed hashes, duplicate
`(algorithm, baseArchiveSha256)` offers, and out-of-range sizes reject the head.
At most 16 descriptors are allowed. Base and target sizes are 1..1 GiB; patch
size is 1..256 MiB in metadata. This codec additionally requires at least 38
patch bytes. Unknown well-formed algorithm identifiers are accepted but ignored.
Omitting `deltas`, or using an empty array, means full delivery only.

Delta offers are delivery alternatives, not release identity. The existing five
release identity fields, admission records, version floors and process leases
retain their meanings. Feed and custom updater/recovery transports continue to
download complete VPKs; the built-in HTTP v1 downloader implements this extension.

## Artifact endpoint

Under the configured delivery base URL, serve:

```text
v1/apps/<applicationId>/releases/<targetReleaseId>/payload.vpk
v1/apps/<applicationId>/releases/<targetReleaseId>/deltas/<baseArchiveSha256>/payload.dvpk
```

The DVPK endpoint uses the same authentication, audience/origin restrictions,
redirect rejection, byte-range semantics and identity content encoding as the
full archive endpoint. Successful responses require:

```http
Content-Type: application/vnd.paravoid.dvpk
ETag: "<patchSha256>"
Content-Length: <patchSize>
```

Use the existing strict Content-Range contract for resumed transfers. No URL
from a patch descriptor is followed; the client constructs the scoped endpoint.
Full VPK responses retain `application/vnd.paravoid.vpk`.

## `bsdiff-deflate-v1` binary format

All lengths and controls below are signed 64-bit **little-endian two's
complement** integers. This differs from BSDIFF40's sign-magnitude integers.
The container is not a BSDIFF40 file and uses no bzip2 dependency on Android.

| Offset | Length | Value |
| --- | --- | --- |
| 0 | 8 | ASCII `DVPKD001` |
| 8 | 8 | Compressed control stream byte count, C |
| 16 | 8 | Compressed difference stream byte count, D |
| 24 | 8 | Target full VPK byte count, N |
| 32 | C | Raw DEFLATE control stream |
| 32+C | D | Raw DEFLATE difference stream |
| 32+C+D | remaining bytes | Raw DEFLATE literal stream |

Each block is one RFC 1951 DEFLATE stream, with no zlib or gzip wrapper. C, D and
the remaining compressed length must each be at least 2 and fit within the
signed patch size. N must equal the authenticated target size. All three streams
must end exactly: reject concatenated streams, trailing compressed bytes,
truncated streams and unconsumed decompressed bytes.

The uncompressed control stream contains records `(add, copy, seek)` of 24
bytes. Initially the base cursor and target cursor are zero. For each record:

1. Read `add` difference bytes. For each, output `(difference + baseByte) mod
   256`, advancing both cursors. Base positions outside the base archive supply
   zero, without reading outside the descriptor.
2. Copy `copy` bytes from the literal stream to the target, advancing only its
   cursor.
3. Add signed `seek` to the base cursor. Negative seeks are supported.

`add` and `copy` must be nonnegative and fit within the remaining target length.
A record producing no output is allowed only if `seek != 0`. There are at most
1,000,000 control records. Reject signed 64-bit overflow in base cursor advances
and seeks, partial control records and unused data. Stop only at exactly N
output bytes and exact end of all three streams. Empty base/target archives are
invalid. Encoders may choose different valid DEFLATE encodings or control
records; reconstruction, not identical patch bytes across encoder versions, is
the interoperability requirement.

The decoder streams bounded chunks from three compressed blocks and uses
positional reads of the base. It does not allocate memory proportional to the
archive or uncompressed streams. Verify patch size/hash, base size/hash from
the same open descriptor, and reconstructed target size/hash. Only afterward
can the full VPK pass through the existing signature/inventory verifier.

## Shell behavior and recovery

The shell admits the signed target and obtains the existing space reservation
before transferring bytes. It considers only a direct patch for the selected
base's exact hash and size, chooses the smallest supported descriptor, and
requires at least a 20% reduction against the full VPK's signed wire size.
An archived version number or matching module dependency graph is insufficient.

It opens the base while selection/cleanup is excluded, then retains a read-only
file descriptor. That descriptor survives unlink on Android/Linux; it does not
acquire an execution lease, activate code, modify selection or count as a trial.
The same descriptor is hashed and read during reconstruction. The active
generation is never patched in place.

The transfer creates a private reconstructed archive, deletes the patch, and
hands the full result to the ordinary reservation staging path. Existing
signature checks, owned archive copying, admission/credential rechecks,
materialization, pending selection and coordinated cold activation still apply.
The existing reservation of `3 * targetArchiveSize + 64 MiB` covers the selected
patch, reconstruction and subsequent staging; no unbounded patch chain is kept.

Missing/corrupt bases, unavailable patches, unsupported algorithms or malformed
patch data fall back to the full artifact under the same authenticated target
admission. Cancellation, authentication denial, expired credentials and stale
admissions propagate through their existing paths instead of starting another
transfer. A failure in final full-VPK verification fails staging normally.
Partial patches are removed after an attempt; complete-VPK partials retain the
existing resume behavior. On the next check, the transfer lock permits cleanup
of an abandoned reconstruction after process death. No partially reconstructed
archive is executable or selected.

## Reference encoder and conformance

[delivery/tools/dvpk.py](delivery/tools/dvpk.py) generates patches from two
already verified archives, using `bsdiff4` (tested version 1.2.6) on the backend.
It converts BSDIFF40 controls to this format and independently reconstructs the
target before atomically publishing the patch. It prints the exact descriptor
and target hash/size as JSON for insertion into signed discovery metadata:

```sh
python3 -m venv /tmp/paravoid-dvpk-tools
/tmp/paravoid-dvpk-tools/bin/pip install bsdiff4==1.2.6
/tmp/paravoid-dvpk-tools/bin/python delivery/tools/dvpk.py base.vpk target.vpk payload.dvpk
```

This reference CLI is a trusted-input, memory-consuming backend tool, not an APK
signature verifier or a hardened service for arbitrary untrusted patches. Stores
must verify inputs first, bound worker memory/CPU, and skip patches when generation
fails or savings are insufficient. R8 settings do not need to change for
correctness: the patch reconstructs complete final optimized archive bytes;
optimizer changes can affect savings but cannot change the required target hash.

Run `bash delivery/test.sh`, `bash lifecycle-tests/run.sh` and
`./gradlew :paravoid-contract:test :paravoid-runtime:assembleDebug` for shell
validation. Tests include Python/Java interoperability with a 32 MiB Java heap,
negative seeks, outside-base arithmetic, chunk boundaries, strict termination,
overflow/operation bounds, corruption, fallback, auth/cancellation and lifecycle
base access. Transport fixtures are explicit opaque test bytes; production
staging always verifies complete signed VPKs.
