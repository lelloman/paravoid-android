# Signed component-inventory experiment

This is a bounded extension of [signed discovery](SIGNED.md), not the finalized
VPK format or a production updater. Component bytes are opaque fixtures; nothing
downloaded is executed, resource-loaded, or extracted to component paths.
The separate [cold-DEX mode](COLD-DEX.md) replaces marker bytes with real DEX and
adds startup-only selection/execution. It does not change this archive-only gate.

The experimental archive is a **stored-only ZIP** containing `manifest.sig`,
`inventory.txt` and 1–16 regular component files. The manifest uses the existing
domain-separated envelope with kind `manifest`, signed by a separate release key.
It binds application, contract, release ID, payload version, format
`stored-inventory-1`, inventory byte length and SHA-256. Inventory lines are sorted
by path and encode `role|path|length|sha256` followed by LF. Roles/prefixes are
`code/code/`, `resources/resources/`, `asset/assets/`, `java-resource/java/` and
`native/native/`. Unknown roles and mismatching prefixes are rejected.

Maximum archive size is 1 MiB; each metadata file is at most 4096 bytes, each
component at most 128 KiB, and component bytes total at most 512 KiB. Paths are
restricted ASCII, at most 96 characters, without empty/dot/parent segments or
absolute paths. Empty components are outside this fixture profile.

Compression, data descriptors, encryption, ZIP64, directory/symlink entries,
extras and ZIP comments are deliberately unsupported. This removes decompression
from this gate rather than claiming compressed ZIP-bomb coverage. The Android
verifier checks local/central directory consistency, contiguous layout, CRCs,
duplicates and bounds before inventory validation. ZIP record layouts follow the
[PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).
The profile accepts the fixture writer's Unix/version-20 headers, mode `100644`,
zero flags and internal attributes only; it is not a permissive ZIP interchange
format. The Python verifier checks the shared semantic vectors using `zipfile`;
the strict raw-layout vectors specifically exercise Android's independent parser.

Python provides a producer and a separate verifier using its ZIP library. Host
tests cover valid inventory, tampered/missing/unexpected/duplicate files, identity,
signer, path/role and size failures. This is not semantic DEX/resource/native
validation, a generated shell-contract check, or a general-purpose ZIP reader.

## Running the Android gate

```sh
ANDROID_HOME=/path/to/sdk python3 compatibility/provisioning/check_signed.py --serial emulator-5584 --archive
```

The signed runner's prerequisites, emulator-only restriction and four fixture
package scope apply. A third ephemeral signing key is generated on the host;
only its public key enters the APK. Trusted APK policy selects archive validation.
The server does not hold any signing keys. All negative archives are bound by
**valid signed discovery metadata**, so an outer digest failure cannot masquerade
as an inner-verifier success. One vector signs the manifest with the trusted
discovery key, which must not act as a trusted release key.
APKs are snapshotted into the runner's private temporary directory after the
build, so a later cleanup of shared Gradle outputs cannot remove its test inputs.

The client verifies the downloaded archive in memory after its outer digest, then
atomically replaces the retained archive file only after every inventory check
passes. It never writes an archive entry to a component path. Tests hash the
previous retained archive after each rejection, and exercise a valid recovery
release followed by process restart/304 reuse. Each negative release gets a new
identity/revision; the verified discovery high-water mark may advance even when
inner verification fails, but the retained archive does not change.

Vectors cover five roles, signature/identity/format failures, inventory/component
tampering, missing/extra/duplicate components, unsafe paths, symlinks, unsupported
method fields, per-entry/aggregate/count bounds, unknown/mismatched roles,
duplicate inventory paths, local/central name mismatch, bad CRC, trailing bytes
and truncation. Reports go to ignored `build/archive-api{SDK}.json`.

The signed head binds the entire archive; this experiment does not yet add a
separate manifest-digest field or the draft's SDK/ABI/runtime/ledger requirements.
Components are role-labelled **opaque marker bytes**, not valid DEX/compiled
resource/ELF test applications. No loading, native execution, extraction-race,
cross-process selection or rollback safety is implied by acceptance here.

## Verified results (2026-09-21)

- All 22 host tests pass, including semantic vectors checked independently by
  Python's verifier.
- All 136 archive device stages pass on API 30 and API 36.1 x86_64, across normal/
  shell and public/key variants. This includes 29 archive vectors plus a cold
  restart per variant, and the keyed grant-validation controls.
- Android `lintDebug` passes.
- The earlier 114-stage signed-discovery suite passes again on API 36.1 without
  archive mode enabled.

The first API 36.1 attempt was interrupted when shared Gradle build outputs
disappeared during the run. It is not counted as a pass. The runner now snapshots
all four APKs into its own temporary directory; the full rerun passed. No personal
device or downstream application was modified.
