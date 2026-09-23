# Track C: packaging and host regression handoff

Baseline `f478ec6`; implementation/test commit `3552502` on `finish/c-packaging`.
The code below was tested at `3552502` in `/tmp/paravoid-finish-c-packaging`.

## Change

- Complete-mode merged-manifest transformation now rejects `directBootAware` and
  `isolatedProcess` component declarations with the component kind/name and
  attribute in the error. It also rejects an application-wide direct-boot flag
  and collisions with the generated launcher or recovery Activity names. The
  check runs before APK signing and before shell packaging.
- In complete mode the named legacy resource-shell task now fails directly with
  guidance to use the complete-shell task, without running packaging dependencies.
  DEX-only mode keeps the existing task.
- Tests cover all four component kinds, both unsupported attributes, reserved
  declarations, component-kind changes in accepted shell baselines, and a signed
  VPK writer failure after temporary archive creation. An existing destination is
  byte-identical and an absent destination stays absent; no temporary archive is
  left. No production contract, signed format or persisted record changed.

## Commands and results

Gradle commands used the exact binary
`/home/lelloman/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle`
with `ANDROID_HOME=/home/lelloman/Android/Sdk`,
`GRADLE_USER_HOME=/tmp/paravoid-finish-c-gradle` and
`GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches`; all used
`--offline --no-daemon --max-workers=2`. These are command outputs from this
branch, not final merged acceptance.

| Command (after the environment above) | Result / local report |
| --- | --- |
| `gradle -p paravoid-gradle-plugin test` | PASS, 85 plugin tests including TestKit complete builds; `paravoid-gradle-plugin/build/reports/tests/test/index.html`. The first run caught a new Groovy qualification error; corrected before the passing rerun. |
| `gradle :paravoid-contract:test :paravoid-runtime:testDebugUnitTest` | PASS; `paravoid-contract/build/reports/tests/test/index.html`, `paravoid-runtime/build/reports/tests/testDebugUnitTest/index.html`. |
| `bash lifecycle-tests/run.sh` | PASS, including process-death publication boundaries, admission and selection. Terminal output recorded in this track run. |
| `bash delivery/test.sh` | PASS, Java host assertions and 12 Python HTTP/personalization tests. Terminal output recorded in this track run. |
| `python3 compatibility/complete-v1/prepare.py` | PASS; created ignored fixture-only keys in this worktree. |
| `gradle -p compatibility/complete-v1 assembleNormalDebug assembleParavoidAndroidDebug` | PASS, 154 tasks; normal APK and signed complete shell/VPK built. |
| `git diff --check` | PASS before commit. |

The fixture build used its default embedded A generation and no accepted baseline.
The plugin TestKit baseline tests and the new component-kind test exercise contract
comparison; this run does not prove a real app's final accepted baseline or a
large artifact limit. No emulator, phone, production HTTPS, physical ARM64,
independent security review, external publication or push was performed.

## Final merged rerun for A

After cherry-picking `3552502`, run from the merged checkout with A's own unique
writable Gradle home, the SDK and read-only cache above:

```
gradle -p paravoid-gradle-plugin test --offline --no-daemon --max-workers=2
gradle :paravoid-contract:test :paravoid-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2
bash lifecycle-tests/run.sh
bash delivery/test.sh
python3 compatibility/complete-v1/prepare.py
gradle -p compatibility/complete-v1 assembleNormalDebug assembleParavoidAndroidDebug --offline --no-daemon --max-workers=2
```

Use the absolute Gradle binary above for `gradle`. Preserve A's fixture state if
using an already accepted baseline; the fixture command here is the default
no-baseline build. Rerun these on the final merged commit before citing them as
combined acceptance. This track's worktree is clean after the handoff commit.
