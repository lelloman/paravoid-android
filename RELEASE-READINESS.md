# v1 release acceptance

This is the bounded release checklist, not a declaration of readiness. Original
acceptance baseline: `509cb65`; later evidence names its tested revisions below
and in the linked reports. Do not substitute host tests for installed tests or independent review.

| Gate | Current evidence / remaining work |
| --- | --- |
| Host/build | Current local regression passes: 185 Gradle tests plus lifecycle/delivery and Python suites. Repeat host regression and local standalone publication/consumer validation on the final candidate. CI repeats these checks; neither approves a release. |
| Installed | Current unified-engine bootstrap/controls and ten retry/cancellation death boundaries pass on disposable API 36.1. API 30, scheduled retry replacement, HTTPS, storage and publication reruns remain pending. |
| Payload R8 | Normal and shell saved-data upgrades pass on API 36.1 with transitive AAR/JAR consumer rules. Real-app integration and complete-profile shrinking acceptance remain pending. |
| Physical ARM64 | No physical device supplied or tested. |
| Authenticated real app | Backend/account and full login/content/playback/native/update/repair evidence needed. |
| Independent security | Independent reviewer and signing-block/private-ID approval needed. |
| Distribution | Nine-module local staging and standalone normal/shell/VPK builds pass in this session. License/version, actual remote build and standalone remote consumer acceptance remain pending. |

## Current candidate evidence and remaining inputs — 2026-10-03

The September reports below are historical evidence. The current update owner is
`UpdateEngine` in `:paravoid_updates`; it persists one `operations.properties`
record rather than the prior controller's retry/cancellation files. Current API
36.1 bootstrap/control and ten installed record-death checks pass; see
[the current-engine report](delivery/device-tests/README.md#current-engine-validation--2026-10-03).
API 30, scheduled retry replacement, release HTTPS, publication and storage reruns
remain separate. No physical device is connected and no authenticated account,
independent reviewer, license or release version has been supplied for this pass.

Audit retained evidence against a full final candidate source SHA:

```sh
python3 release-tests/acceptance.py --plan release-tests/acceptance-plan.json \
  --revision FULL_FINAL_SOURCE_SHA
```

The checked-in plan deliberately lists all unresolved gates. Copy it into the
local evidence directory and fill in actual results. A passing gate needs
`status: "passed"`, the exact `revision`, `command`, `platform`, `recordedBy`, and
an `evidence` array of `{ "path": "relative/log/file", "sha256": "HEX_HASH" }`.
Paths resolve relative to the plan. Record `deviceType: "physical-arm64"` for
physical testing, the full `workflows` list (`login`, `content`, `playback`,
`native`, `update`, `repair`) for the authenticated app, `reviewType: "independent"`
for security, and the actual HTTPS `repositoryUrl` for remote consumer validation.

The read-only audit exits 1 for missing/stale/changed evidence and 2 for invalid
inputs. It checks records and file hashes; it does not infer success from log text,
verify reviewer identity, replace independent review, or approve a release. Even
complete records report `releaseApproved: false`. License/version/signing metadata
still go through [candidate preparation](release-tests/CANDIDATE.md); publication
still requires the owner's concrete release decision. Never fill a gate with host
or emulator evidence to stand in for the required physical or external result.

## Scope and completion rules

- Local implementation, tests and documentation may proceed while external inputs
  are requested. No phone use, other-repository edits, production credentials,
  remote publication or push without the required direction.
- Mark gates passed only with exact commands/revision/platform evidence. If a
  narrower supported contract is chosen, document its exclusions explicitly.
- Process termination does not establish physical power/cache-loss durability.
- Independent security review is not replaced by self-review or another copy of
  the same automated tests.
- Deferred v1 features remain deferred: configuration-targeted delivery, hot swap, dynamic
  manifest changes, data rollback, online root rotation, isolated/direct-boot
  payloads, shell AAB. Payload shrinking is experimental and still needs release acceptance.

## External decisions requested

Public destination selected: GitHub/JitPack, with Fucina internal. Local
JitPack-coordinate publication and standalone consumer pass; actual GitHub push,
JitPack build and remote consumer validation have not been performed. See
release-tests/JITPACK.md. Remaining choices: license and release version; physical ARM64 test device;
independent reviewer. Prepare local-only publication validation pending those
choices. Do not claim these gates complete if no answer is available.

The controls fallback is implemented and validated on API 30/36.1; every OEM
launcher's appearance is not covered by that evidence. Four release
groups still need acceptance: coordinated final Pezzottify workflows; physical
ARM64; independent security/signing-block review; and actual publication
configuration/version/license and remote GitHub/JitPack validation. Internal audit and local release
tooling do not waive those gates. Parallel finishing work uses isolated worktrees;
only reviewed commits and explicitly recorded test results count as integrated.

The real-app group now means the remaining authenticated/content/playback/native
workflows, not the already-passed logged-out delivery/recovery matrix. The run
found and fixed a real controls observer race (`72f5816`), reproduced failure on
the old shell and passed both the dedicated API 30/36.1 regression and final
Pezzottify repeated-controls sequence. The runtime fix correctly requires a new
shell baseline; final positive VPKs were rebuilt through the production plugin,
not relabelled to bypass the old contract. A signed rejected head also consumes
its payload identity/version: corrected releases must advance the version, as
clarified in V1.md. No new release gate was waived to obtain these results.

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

## Historical acceptance matrix

These results describe earlier revisions and controllers. They do not satisfy the
current candidate gates without a recorded rerun.

| Gate | Remaining acceptance | Status |
| --- | --- | --- |
| Persistence | First/replacement retry/cancel writes, deletion, bounded temporary slots and documented durability limits | Host matrix and 22 installed boundaries/API pass on API 30/36.1; see delivery/PERSISTENCE-TESTS.md |
| Startup | Supported factory construction and provider/Activity/service startup; later crashes excluded | Seven installed cases/API on API 30/36.1 pass; explicit scope/handler limits in integration-v1/STARTUP-FAILURES.md |
| Recovery races | Installed stale identity, pending repair, corruption and live lease refusal | Passed on API 30/36.1; pending-repair lifecycle gap fixed; see integration-v1/STARTUP-FAILURES.md |
| Controls access | Document and test fallback when dynamic shortcut unavailable | App-owned observable update state and controls implemented; extra launcher entry is opt-in. API 30/36.1 cold/offline/shortcut-absent checks pass with the fixture's explicit alias; see integration-v1/CONTROLS-LAUNCHER.md |
| Storage/processes | Selected distinct-release contention/death/cancel and publication I/O; restart identity/stall limits | Selected matrix passed on both APIs; see delivery/device-tests/README.md and V1.md restart limits |
| HTTPS/auth | Signed release HTTPS download, personalized install, revoke/replace, interrupted transfer and fail-closed grants | Passed on API 30/36.1; local TLS and scope in delivery/device-tests/HTTPS-RELEASE.md |
| Security/signing | Signing-block ID collision audit and independent protocol/security review | Scoped internal audit and AOSP ID survey recorded in delivery/SECURITY-REVIEW-2026-09-23.md; allocation-amplification fix tested; independent reviewer/private-ID approval still needed |
| Real app | Final-runtime Pezzottify fixed-shell A-to-B, data/workflows, incompatible rejection and forward repair | Logged-out API 30 matrix passes with final-runtime producer-built A/B/4/5, preference/Room checks, incompatible/reused-identity refusal, quarantine/repair and offline relaunch; authenticated workflows still need approved backend/account; see release-tests/PEZZOTTIFY.md |
| Physical device | Signed release ARM64 core matrix | Guarded HTTPS runner and nine host safety/routing tests prepared; no physical hardware connected/executed; see delivery/device-tests/PHYSICAL-ARM64.md |
| Packaging/regression | Large valid payload execution, publication failures, optional integrations and final matrix | Near-limit VPK runs on both APIs; final-rename IO and 159-test host bundle pass; see delivery/device-tests/LARGE-PAYLOAD.md and release-tests/README.md |
| Distribution | Version/license/repository, production metadata/tag/release notes, repeatable validation | GitHub/JitPack selected; changelog and skipped-version comparison added, with a version-tag notes gate. Six-module local publication/consumer tests pass; license/tag and actual remote build/consumer remain pending; see release-tests/JITPACK.md |
