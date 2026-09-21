# Signed component-inventory experiment

This is a bounded extension of [signed discovery](SIGNED.md), not the finalized
VPK format or a production updater. Component bytes are opaque fixtures; nothing
downloaded is executed, resource-loaded, or extracted to component paths.

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

Python provides a producer and a separate verifier using its ZIP library. Host
tests cover valid inventory, tampered/missing/unexpected/duplicate files, identity,
signer, path/role and size failures. This is not semantic DEX/resource/native
validation, a generated shell-contract check, or a general-purpose ZIP reader.
