# Integrated finishing pass — 2026-09-23

Reviewed and combined A/B/C on `finish/integrated`. Tested implementation commit:
`a41d7c3`; subsequent integration changes are documentation only. This pass is
combined engineering validation, not production release approval.

## Imports

| Track | Integrated commits |
| --- | --- |
| A | `b7980fa`, `8c2cff1`, `d5d3e60` (original commits retained) |
| B | `279d293` → `11995b6`; `1b923b8` → `be9ada0` |
| C | `3552502` → `73b6541`; `62ac35c` → `a41d7c3` |

The only cherry-pick conflict was in `delivery/test.sh`: both new test invocations
were retained. No protocol, persisted-format or public lifecycle-interface change
was needed. Track branches/worktrees are retained for audit.

## Fresh combined validation

Gradle environment: SDK `/home/lelloman/Android/Sdk`, writable user home
`/tmp/paravoid-track-a-gradle`, read-only dependency cache
`/home/lelloman/.gradle/caches`, Gradle 8.13 binary as in `PLAN.md`.
All Gradle commands used `--offline --no-daemon --max-workers=2 --console=plain`.

| Command | Result | Log under `/tmp/` |
| --- | --- | --- |
| `gradle -p paravoid-gradle-plugin test` | 85 tests, no failures/errors/skips | `paravoid-merged-plugin.log` |
| `gradle :paravoid-contract:test :paravoid-runtime:testDebugUnitTest` | 28 contract + 17 runtime tests, no failures/errors/skips | `paravoid-merged-units.log` |
| `bash lifecycle-tests/run.sh` | PASS, including publication and recovery-control regressions | `paravoid-merged-lifecycle.log` |
| `bash delivery/test.sh` | PASS, including both integrated tests and 12 Python tests | `paravoid-merged-delivery.log` |
| `bash delivery/check-android.sh` | Android 36 source compile PASS | `paravoid-merged-android.log` |
| Complete fixture normal APK + shell APK + VPK tasks, empty/public A/version 1 | PASS | `paravoid-merged-fixture.log` |
| Same fixture tasks, embedded/public broken/version 2 | PASS | `paravoid-merged-broken-build.log` |

Fixture tasks: `-p compatibility/complete-v1 assembleNormalDebug
assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk`, with
`-Pbootstrap=empty -Pauthentication=public -Pgeneration=A -PpayloadVersion=1`
or `-Pbootstrap=embedded -Pauthentication=public -Pgeneration=broken -PpayloadVersion=2`.
No accepted baseline or shared-UID probe was enabled for these builds.

Fresh installed tests on Restart30/API 30 (`emulator-5586`) and Restart36/API 36.1
(`emulator-5584`), x86_64:

- `python3 -u delivery/device-tests/public_bootstrap.py --serial SERIAL --restart-controls`
  (`--server-port 18766` for API 36): signed download, shell controls, cancellation,
  confirmation cancellation, confirmed offline activation and recovery survival.
  Logs: `paravoid-merged-public30.log`, `paravoid-merged-public36.log`.
- `python3 -u integration-v1/quarantine-controls.py --serial SERIAL --avd AVD
  --apk compatibility/complete-v1/build/outputs/paravoid/paravoidAndroidDebug/shell.apk`
  after the broken fixture build: actual startup quarantine, cancelled/confirmed
  exact-generation retry, explicit restart and repeated failure/re-quarantine.
  Recovery stays alive and payload-free. Logs: `paravoid-merged-quarantine30.log`,
  `paravoid-merged-quarantine36.log`.

All passed. Restored ordinary empty/public A build outputs afterward
(`paravoid-merged-restore-build.log`). Both disposable emulators stopped. No phone,
publication, push or other-repository change. `REMAINING-GAPS.md` remains untracked.

## Evidence boundaries and remaining work

Track A's full three-boundary signed journal-death/retry device matrix remains
evidence at its recorded branch builds (see `TRACK-A.md`), not a matrix rerun on
the final combined APK. Combined host tests cover the publication boundaries.
Track B's stale confirmation, corrupt bytes and pending-repair regressions are
host evidence; this integration adds the installed intact-quarantine path only.

Still unresolved, not silently waived:

- Broader installed writer/death/cancellation interleavings, actual power/cache-loss
  behavior and raw retry/cancellation temporary-write/rename interruption cases.
  Follow-up: overlapping HTTP-staging/embedded admission under real exhaustion now
  passes on both APIs at archive and component writes; see
  `delivery/device-tests/README.md`. This does not cover two HTTP downloads or
  two different releases racing end-to-end.
- Additional early component/factory startup failures, corrupt/pending-repair UI
  device races and durable discovery of controls on unsupported/throttled launchers.
- Large artifact limits, real-app updates under the final combined runtime,
  production HTTPS personalization, signing-block/v4 ecosystem review, independent
  security review and physical ARM64 acceptance.

Legacy directory cleanup, earlier manifest diagnostics, focused component-baseline
and failed-VPK-output regressions, and the named combined host/build suites are
now implemented/validated; they should no longer be listed as wholly missing.
