# Three-track finishing pass

Baseline: `de7d941`. This is a finite engineering pass, not a promise to test every
possible Android behavior or a declaration of release readiness.

## A — storage and delivery durability (primary agent)

1. Installed signed-VPK publication/journal interruption tests, including the
   before/after atomic-selection distinction, active preservation and retry.
2. Audit remaining retry/cancellation persistence boundaries and legacy delivery
   directory cleanup; implement bounded fixes/tests or document a concrete deferral.
3. Integrate B/C and rerun the combined suite on the resulting commit.

Owns lifecycle storage/admission/publication and delivery controller/persistence.
Uses the main checkout on `finish/a-storage`, the existing Restart30/Restart36
AVDs (5586/5584) and `/tmp/paravoid-track-a-gradle`.

## B — recovery controls and quarantine

1. Stale quarantine-retry confirmation must not act on a newly selected identity.
2. Corrupt selected bytes, pending forward repair and live leases must have correct
   recovery actions; do not offer misleading retries or reset replay state.
3. Audit launcher shortcut failure/ownership behavior; document a bounded fallback
   or limitation. Separate genuine startup failure from ordinary later crashes.

Owns recovery UI, shortcut code, dedicated recovery tests and `TRACK-B.md`.
Coordinate with A before editing shared lifecycle storage/selection classes;
send a minimal proposed change rather than silently changing their contracts.
Stop this pass after the three items have tests/fixes or explicit evidenced limits.

## C — packaging and regression

1. Earlier actionable diagnostics for unsupported direct-boot/isolated components
   and reserved shell declarations; audit legacy task rejection in complete mode.
2. Add targeted baseline/component and failed-output publication regressions.
3. Run and record the available combined plugin/contract/runtime/lifecycle/delivery
   host suites and fixture builds on this baseline plus C changes. Supply exact
   commands for A's final merged rerun; do not label a branch-only pass as final.

Owns Gradle plugin, packaging tests and `TRACK-C.md`. No emulator/phone use needed.
Stop after this bounded pass; report large-artifact/security/physical-device gates
separately rather than expanding scope indefinitely.

## Shared interface and working rules

- Public protocol, signed formats, Lifecycle/GenerationLease/DownloadReservation
  interfaces and persisted record formats are frozen for independent work. Propose
  any necessary change to A first; no incompatible local invention.
- No rollback, replay-floor reset, artificial lease release or verifier bypass.
  An error after atomic rename can coexist with valid pending state.
- Each track uses its own branch/worktree, build outputs, temporary fixtures and
  writable Gradle user home. SDK and read-only dependency cache may be shared.
- B must create its own disposable AVDs/userdata, use explicit serials (ports
  5590/5592 reserved for B), and separate host HTTP ports (18865/18866). Do not run
  A's AVDs or share writable disk images. Start host-first and limit device runs to
  one emulator at a time if host resources are tight. C runs host/build checks.
- SDK: `/home/lelloman/Android/Sdk`; Gradle 8.13 binary:
  `/home/lelloman/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle`.
  Read-only cache: `GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches`.
  Prefer offline builds, `--max-workers=2 --no-daemon`, and unique writable caches.
- No phones, publishing, push, remote messages, cross-repo changes or automatic
  history rewrites. No new subagents under B/C. Small commits, scoped fixes.
- Do not edit the primary checkout's untracked `REMAINING-GAPS.md` from B/C; A owns
  it and keeps it untracked. Avoid concurrent edits to V1.md/shared handoff docs;
  put track-specific evidence and proposed spec wording in your own handoff.
- Handoff: commit IDs, exact commands/logs, tested commit/device/API, evidence
  limits, unresolved items and whether worktree/emulators are clean/stopped.
- A reviews and cherry-picks completed track commits, then runs integration tests.
  Final release classification must distinguish required gates from follow-up
  hardening. HTTPS personalization, physical ARM64, independent security review
  and real-app final acceptance are not silently waived by these tracks.
