# Track A finishing work

Baseline: `main` at `7425642`. Branch: `v1/packaging-finish`; worktree:
`/tmp/paravoid-v1-packaging-finish`.

Owns Gradle packaging, baseline/report outputs, signing/verification formats,
real-app/AGP integration and final cross-track integration. Track B owns `delivery/`;
Track C owns `paravoid-runtime/`, `lifecycle-tests/` and `compatibility/complete-v1/`.
Do not edit those trees or shared interfaces without coordinating changes.

Builds use `/tmp/paravoid-track-a-gradle` as a separate Gradle user home and
`GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches` for read-only dependency reuse.
Included projects must resolve inside this worktree. No phone, publishing or
remote push is authorized by the track assignment.

## Verified slices

- Added required root-level shell contract, resource ledger and JSON/text packaging
  reports, including ownership, pinned chains, public key IDs, limitations and
  baseline changes. Reports are generated before a compatibility gate fails.
- Added `payload.sha256`; retained the prior filename as an identical alias.
- TestKit tests pass for automatic embedded/empty assembly and signed VPK baseline
  updates, now also asserting report content, public output locations, compatibility
  failures and unchanged normal APKs. Existing shell-contract unit tests pass.

This evidence is build-level, not installed-device acceptance. Remaining Track A
work includes complete-profile boundary tests, real-app AGP integration, publishing
artifact correctness and cross-track acceptance after B/C handoff.
