# v1 release acceptance

This is the bounded release checklist, not a declaration of readiness. Baseline:
`509cb65`. Do not substitute host tests for installed tests or independent review.
Keep `REMAINING-GAPS.md` as the untracked detailed local history.

| Gate | Remaining acceptance | Status |
| --- | --- | --- |
| Persistence | Installed retry/cancel publication interruption; retry deletion; temporary-file reclamation and explicit durability limits | Host 20-case matrix passes; installed boundaries open |
| Startup | Define and cover early factory/provider/Activity/service failures without quarantining ordinary later crashes | Open |
| Recovery races | Installed stale confirmation, corrupted bytes, pending repair and live lease refusal | Host passes; device open |
| Controls access | Document and test fallback when dynamic shortcut unavailable | Product/implementation decision open |
| Storage/processes | Selected distinct-release contention/death/cancel and publication I/O; review restart identity/stalls | Installed exhaustion/admission passes; broader gates open |
| HTTPS/auth | Signed release HTTPS download, personalized install, revoke/replace, interrupted transfer and fail-closed grants | Production-like acceptance open |
| Security/signing | Signing-block ID/v4 disposition, vector audit, independent protocol/security review | Open; independent reviewer needed |
| Real app | Final-runtime Pezzottify fixed-shell A-to-B, data/workflows, incompatible rejection and forward repair | Open; downstream edits require coordinated scope |
| Physical device | Signed release ARM64 core matrix | No device connected; device authorization needed |
| Packaging/regression | Large valid payload execution, publication failures, optional integrations and final exact-revision matrix | Exact structural bounds pass; broader acceptance open |
| Distribution | Version/license/repository, production metadata/tag/release notes, repeatable validation | Local staging/standalone consumer pass; user choices and remote setup pending |

## Scope and completion rules

- Local implementation, tests and documentation may proceed while external inputs
  are requested. No phone use, other-repository edits, production credentials,
  remote publication or push without the required direction.
- Mark gates passed only with exact commands/revision/platform evidence. If a
  narrower supported contract is chosen, document its exclusions explicitly.
- Process termination does not establish physical power/cache-loss durability.
- Independent security review is not replaced by self-review or another copy of
  the same automated tests.
- Deferred v1 features remain deferred: targeted/delta delivery, hot swap, dynamic
  manifest changes, data rollback, online root rotation, isolated/direct-boot
  payloads, shell AAB and shrinking.

## External decisions requested

Artifact repository, license and release version; physical ARM64 test device;
independent reviewer. Prepare local-only publication validation pending those
choices. Do not claim these gates complete if no answer is available.

## Focused evidence added during release work

- Local publication/standalone consumer: `release-tests/README.md` (both default
  and alternate coordinated version; no remote publication).
- Archive boundaries: `ArchiveLimitsTest`, full contract suite passed on
  2026-09-23 (`/tmp/paravoid-archive-limits.log`). Covers a sparse 1 GiB/+1 size
  guard probe, 4,096/4,097 entries, 255/256-byte names and 1 MiB/+1 envelope reads.
  Sparse size rejection is not verification/loading of a valid 1 GiB payload;
  structural envelope reads are not acceptance of unsigned metadata.
