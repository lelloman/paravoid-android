# Track B — recovery controls and quarantine

Baseline `f478ec6`; branch `finish/b-recovery`; worktree `/tmp/paravoid-finish-b-recovery`.

## Changes

- `279d293` — The recovery screen offers generation retry only for a selected,
  quarantined startup failure (`UNAVAILABLE`) with no pending forward generation.
  It hides retry for selected-byte integrity failure, pending repair, and a
  snapshot reporting a live selected-process wait. Confirmation still captures
  the exact `ExpectedArchive`; lifecycle is the final authority after the dialog
  and asynchronous dispatch. No persisted or public interface changed.
- Host regressions use the real `RuntimeLifecycle`, journal, materialized bytes,
  and an OS child-process exit to prove stale confirmed identity refusal after
  generation 2 becomes selected, corruption refusal without clearing quarantine,
  and live-lease refusal. A direct retry of quarantined generation 1 while
  generation 2 is pending does not displace generation 2 at the next cold acquire.
  The separate pure UI policy test covers all displayed/hidden states.

## Verification

All on `279d293`, Linux host, 2026-09-23:

| Command | Result |
| --- | --- |
| `bash lifecycle-tests/run.sh` | PASS, including `RecoveryControlTest` and full lifecycle host suite |
| `bash delivery/test.sh` | PASS, including `RecoveryActionsTest` and delivery host/Python suites |
| `bash delivery/check-android.sh` | PASS, Android 36 source compile |
| `git diff --check` | PASS |

The recovery test uses fake test-source archive evidence; it is not signed VPK
or installed Android evidence. Existing API 30/36.1 device results for actual
startup quarantine, confirmation, shortcut navigation, and restart are recorded
in the primary untracked `REMAINING-GAPS.md` and are not rerun on this branch.
Track A must rerun combined suites after cherry-picking this commit.

## Shortcut and startup audit

`ShellControlShortcut.install` runs in main/recovery process construction,
registers only the reserved `paravoid.updates` dynamic shortcut with
`addDynamicShortcuts`, and catches null service, false return, and runtime
failure. It does not quarantine a usable payload on registration failure.
The shortcut routes directly to shell-only `ShellUpdatesActivity`; launcher
fallback routes there when complete-profile payload loading failed. A launcher
with no dynamic shortcut support, a throttled registration, or later payload
shortcut management can still make controls undiscoverable while the payload
is runnable. This pass makes no claim of durable shortcut ownership against
payload code or launcher policy; a separate reserved-ID enforcement/product
entry-point decision is needed for that stronger guarantee.

`ShellApplication` calls `complete.failed()` only during payload attachment,
loader/application/factory construction, and payload Application `onCreate`
exceptions or linkage errors. Those are startup boundaries. It does not wrap
arbitrary later Activity/service/provider callbacks in a quarantine handler;
later crashes therefore are not mislabeled startup failures. A provider/factory
construction failure outside those catches, a non-LinkageError `Error`, or an
exception before `CompleteRuntime` is fully assigned may terminate startup
without a durable quarantine reason. Broadening startup failure capture needs
care around Android component timing and was not changed in this bounded pass.

## Remaining limits and handoff

- The snapshot has no public selected-lease bit when there is no pending
  generation, so the UI can briefly offer retry for a live lease; the real
  lifecycle rejects it and keeps quarantine. Adding such a bit would change a
  frozen public interface, and is not required for safe refusal.
- A fresh pending generation wins cold acquisition even after direct retry of
  an older quarantined selection. The UI hides old retry to avoid a misleading
  choice. No shared lifecycle change was needed; this was coordinated with A.
- The screen's snapshot is advisory; state can change after render or while a
  dialog is open. The exact identity and lease/byte checks at commit time are
  the durable protection.
- No new emulator was created or used; no phone, publication, push, other repo,
  or shared handoff file was touched. Worktree is clean after the handoff commit.
