# v1 release acceptance

This is the bounded release checklist, not a declaration of readiness. Baseline:
`509cb65`. Do not substitute host tests for installed tests or independent review.
Keep `REMAINING-GAPS.md` as the untracked detailed local history.

| Gate | Remaining acceptance | Status |
| --- | --- | --- |
| Persistence | First/replacement retry/cancel writes, deletion, bounded temporary slots and documented durability limits | Host matrix and 22 installed boundaries/API pass on API 30/36.1; see delivery/PERSISTENCE-TESTS.md |
| Startup | Supported factory construction and provider/Activity/service startup; later crashes excluded | Seven installed cases/API on API 30/36.1 pass; explicit scope/handler limits in integration-v1/STARTUP-FAILURES.md |
| Recovery races | Installed stale identity, pending repair, corruption and live lease refusal | Passed on API 30/36.1; pending-repair lifecycle gap fixed; see integration-v1/STARTUP-FAILURES.md |
| Controls access | Document and test fallback when dynamic shortcut unavailable | Product/implementation decision open |
| Storage/processes | Selected distinct-release contention/death/cancel and publication I/O; restart identity/stall limits | Selected matrix passed on both APIs; see delivery/device-tests/README.md and V1.md restart limits |
| HTTPS/auth | Signed release HTTPS download, personalized install, revoke/replace, interrupted transfer and fail-closed grants | Passed on API 30/36.1; local TLS and scope in delivery/device-tests/HTTPS-RELEASE.md |
| Security/signing | Signing-block ID collision audit and independent protocol/security review | Review packet in RELEASE-SECURITY-REVIEW.md; v4 refusal defined/tested, not v4 regeneration support; reviewer needed |
| Real app | Final-runtime Pezzottify fixed-shell A-to-B, data/workflows, incompatible rejection and forward repair | Open; downstream edits require coordinated scope |
| Physical device | Signed release ARM64 core matrix | No device connected; device authorization needed |
| Packaging/regression | Large valid payload execution, publication failures, optional integrations and final matrix | Near-limit VPK runs on both APIs; final-rename IO and 159-test host bundle pass; see delivery/device-tests/LARGE-PAYLOAD.md and release-tests/README.md |
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

Five gate groups remain open: controls fallback (extra launcher entry versus an
app-provided entry, then implementation/test); coordinated final Pezzottify
workflows; physical ARM64 acceptance; independent security/signing-block review;
and actual publication configuration/version/license/repository. The selected
local engineering/validation gates above are complete within their documented
scope. This is not a claim that those five release gates have been waived.

## Focused evidence added during release work

- Local publication/standalone consumer: `release-tests/README.md` (both default
  and alternate coordinated version; no remote publication).
- Archive boundaries: `ArchiveLimitsTest`, full contract suite passed on
  2026-09-23 (`/tmp/paravoid-archive-limits.log`). Covers a sparse 1 GiB/+1 size
  guard probe, 4,096/4,097 entries, 255/256-byte names and 1 MiB/+1 envelope reads.
  Sparse size rejection is not verification/loading of a valid 1 GiB payload;
  structural envelope reads are not acceptance of unsigned metadata.
- Combined regression: 159 plugin/Hilt/Work/contract/runtime tests passed, plus
  lifecycle/delivery host suites. Fixture dependency gaps found by the initial
  run were corrected and rerun; see `release-tests/README.md`. This is current
  build/host evidence, not completion of open device/security/real-app gates.
- Final ordinary public/restart controls and signed release HTTPS reruns passed
  on API 30/36.1 against production `d7f7072`. Logs:
  `/tmp/paravoid-final-public{30,36}.log`, `/tmp/paravoid-final-https{30,36}.log`.
  Local publication/standalone consumer passed again at `4815fb3`:
  `/tmp/paravoid-final-publication.log`, local repository
  `/tmp/paravoid-local-publication.ZkIgPX`. No remote publication or phone use.
