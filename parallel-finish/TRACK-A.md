# Track A — storage and delivery handoff

Branch `finish/a-storage`, starting at `f478ec6`. Root owns integration. This
track made no changes to public protocol or persisted record formats and did not
weaken replay floors, verifier checks, or lease ownership.

## Commits

- `b7980fa` — reclaim old process-partitioned delivery partials under their own
  transfer locks; sync parent directories after retry/cancellation atomic rename
  and retry deletion. The cleanup leaves lock files and directories in place to
  avoid splitting an open lock inode. It preserves unknown files and `shared-v1`.
- `8c2cff1` — test-only JDWP death gate at all three `AtomicRecord` selection
  boundaries, using the installed signed A/B fixture. No production fault hook.

## Verification

- `bash delivery/test.sh` passed, log `/tmp/paravoid-track-a-delivery-fsync.log`.
  Includes legacy cleanup under a busy transfer lock, scoped file ownership,
  shared-directory preservation, and existing cancellation/retry tests.
- `bash lifecycle-tests/run.sh` passed, log `/tmp/paravoid-track-a-lifecycle.log`.
- `:paravoid-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2`
  with the same Gradle environment passed; log `/tmp/paravoid-track-a-runtime-unit.log`.
- `ANDROID_HOME=/home/lelloman/Android/Sdk GRADLE_USER_HOME=/tmp/paravoid-track-a-gradle GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches /home/lelloman/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle :paravoid-runtime:assembleDebug --offline --no-daemon --max-workers=2` passed; log `/tmp/paravoid-track-a-runtime-build.log`.
- Fixture A build with the same Gradle environment and flags, `-p compatibility/complete-v1 assembleNormalDebug assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk -Pbootstrap=empty -Pauthentication=public -Pgeneration=A -PpayloadVersion=1 --offline --no-daemon --max-workers=2`, passed; log `/tmp/paravoid-track-a-fixture-build.log`. Promoted its `baseline-candidate` to ignored `build/accepted/paravoidAndroidDebug`, then built B with `-Pgeneration=B -PpayloadVersion=2 -Pbaseline` (log `/tmp/paravoid-track-a-fixture-b-build.log`). Immutable B output is `/tmp/paravoid-track-a-signed-b.rovqlf`. Rebuilt A (log `/tmp/paravoid-track-a-fixture-a-rebuild.log`). Contract IDs match. These are test-only keys in ignored build directories.

### Installed journal gates

The gate attaches to the real debug recovery process, filters the `AtomicRecord`
breakpoint stack to `SelectionJournal.pending`, then calls JDWP `VirtualMachine.exit`
at the selected line. The process dies while A main and worker hold generation
leases. The harness compares the private checksummed selection record and active
archive hashes, checks the selected/pending identities, and then a separate
explicit retry run confirms offline B activation. The signed head revision rises
between attempts because a newly signed head at the same revision correctly gets
`IDENTITY_CONFLICT`. Signed head admission advances the security record before
selection publication; the gate does not claim a byte-identical security record
across an admission.

API 30 `Restart30`, `emulator-5586`, x86_64, **passed** all three deaths and all
three follow-up retries. Logs:

- `/tmp/paravoid-journal30-before.log` and `...-retry.log` — content synced,
  selection unchanged, retry stages pending B and confirmed
  offline restart activates B.
- `/tmp/paravoid-journal30-renamed.log` and `...-renamed-retry.log` — selection
  contains pending B after rename, A survives, retry recognizes pending B and
  confirmed offline restart activates it.
- `/tmp/paravoid-journal30-dirsync.log` and `...-dirsync-retry.log` — same valid
  pending state after directory sync and successful offline activation.

API 36.1 `Restart36`, `emulator-5584`, x86_64, **passed** the same three deaths
and three explicit retry/offline B activation runs against rebuilt A and matching
B. Logs are `/tmp/paravoid-journal36-before.log`, `...-retry.log`,
`...-renamed.log`, `...-renamed-retry.log`, `...-dirsync.log`, and
`...-dirsync-retry.log`. API 30 used the A shell fixture built at the `f478ec6`
baseline; publication runtime was unchanged by Track A. API 36.1 used the A
shell rebuilt from `8c2cff1`, including `b7980fa` delivery changes.

Each gate uses `python3 delivery/device-tests/public_bootstrap.py --serial SERIAL
--server-port PORT` on a freshly reset disposable fixture, followed by
`PYTHONDONTWRITEBYTECODE=1 python3 integration-v1/controls-shortcut.py --serial
SERIAL --restart-worker --pending-update /absolute/B-output --journal-death
BOUNDARY --head-revision 2 --server-port PORT`. Retry omits `--journal-death` and
uses `--head-revision 3`; higher revisions were used for API 30 harness reruns
after an interrupted diagnostic attempt. A fresh bootstrap resets the fixture.

The installed API 36.1 legacy cleanup probe then created a disposable old
`paravoid-delivery/<package>_paravoid_recovery` directory containing one
64-hex `.part` and `auth-denied`, plus an unknown sentinel in `shared-v1`.
After `adb -s emulator-5584 shell am force-stop` and a cold launcher start, the
old directory contained only `transfer.lock`, the shared sentinel remained, and
the B Activity launched. The test-created sentinel and lock-only directory were
removed after another force-stop. This checks installed startup cleanup; busy
lock and unknown-file behavior are covered by the host test.

The installed cleanup probe used these commands (the `touch` files were empty
test markers; all named test paths were removed afterward):

```sh
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid mkdir no_backup/paravoid-delivery/com.lelloman.paravoidcompat.complete.paravoid_paravoid_recovery
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid touch no_backup/paravoid-delivery/com.lelloman.paravoidcompat.complete.paravoid_paravoid_recovery/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.part
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid touch no_backup/paravoid-delivery/com.lelloman.paravoidcompat.complete.paravoid_paravoid_recovery/auth-denied
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid touch no_backup/paravoid-delivery/shared-v1/track-a-sentinel
adb -s emulator-5584 shell am force-stop com.lelloman.paravoidcompat.complete.paravoid
adb -s emulator-5584 shell am start -W -n com.lelloman.paravoidcompat.complete.paravoid/com.lelloman.paravoidandroid.runtime.LauncherActivity
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid ls -la no_backup/paravoid-delivery/com.lelloman.paravoidcompat.complete.paravoid_paravoid_recovery
adb -s emulator-5584 shell run-as com.lelloman.paravoidcompat.complete.paravoid ls no_backup/paravoid-delivery/shared-v1/track-a-sentinel
```

## Audit limits and proposed specification wording

Retry and cancellation record replacement now syncs the parent directory, and
retry deletion syncs when a record existed. This strengthens the intended
process-death persistence boundary. It is not power-loss evidence. Temporary
`retry*.tmp` files left by a killed writer are small and ignored by the reader;
cleaning them on the next owned attempt is a bounded follow-up, with ownership
and cross-process lock ordering kept explicit.

Legacy per-process delivery directories can contain partial VPK copies and marker
files from an older app version. Current startup reclaims recognized files only
while each old transfer lock is free. A busy old writer is left alone. The old
directory and lock file stay in place so a permanent lock channel cannot be
unlinked and replaced under another process. Unknown files are preserved. This is
a bounded space cleanup, not a full directory migration.

Suggested shared V1 wording: "Installed signed-VPK debugger-terminated recovery
tests on disposable API 30 and 36.1 emulators distinguish process death before
the atomic selection rename from death after rename and after directory sync.
Selected A bytes and live leases survive; pre-rename retry stages B,
while post-rename retry retains pending B. Confirmed offline restart activates B.
The tests observe process death, not actual power loss, filesystem cache loss, or
simultaneous writers under exhaustion."

No physical device, production HTTPS publication, or independent security review
was performed. Other open gates: simultaneous installed writers under exhaustion,
real power loss or cache loss at fsync boundaries, and raw retry/cancellation
temporary-file write/rename interruption tests. Combined results after B/C
integration belong to the root handoff.

At handoff, the only worktree change was root-owned untracked
`REMAINING-GAPS.md`. Both reserved emulators were stopped; `adb devices -l`
showed no attached device. The manually injected device files were removed,
and no phone was touched.
