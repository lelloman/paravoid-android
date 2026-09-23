# Local release staging

`bash release-tests/regression.sh` runs the plugin, optional Hilt/Work, contract,
runtime, lifecycle and delivery host suites plus Android delivery compilation.
It accepts the Gradle/cache/offline variables below and deliberately does not
select a connected device. Installed/physical and real-app gates remain separate.

`bash release-tests/local-publication.sh` publishes all six projects to a fresh
local Maven directory and builds a standalone consumer against those artifacts.
The consumer has no included build, source dependency or project substitution.
It resolves the main/Hilt/Work plugin markers; only the main plugin is applied.
Hilt/Work functional coverage remains in their respective integration suites.

Prerequisites: JDK 17+, Android SDK 36 (`ANDROID_HOME`), Python cryptography and
the build's Gradle 8.13/tooling dependencies. Optional environment:

- `PARAVOID_GRADLE`: absolute Gradle executable (default `./gradlew`).
- `PARAVOID_GRADLE_USER_HOME`: isolated writable dependency cache.
- `PARAVOID_OFFLINE=true`: require already-cached external dependencies.
- `PARAVOID_VERSION`: coordinated candidate version; default `0.1.0-dev`.
- `PARAVOID_LICENSE_NAME` and `PARAVOID_LICENSE_URL`: optional paired,
  owner-approved POM license metadata; neither has a default.

The repository is retained at the printed `/tmp/paravoid-local-publication.*`
path for inspection. Only local-file staging is configured; no remote upload,
credentials, Maven Central/Plugin Portal approval or artifact signing is implied.
Android API/runtime release AARs, contract/plugin JARs, sources, dependency metadata
and plugin markers are published. Consumer APKs are debug-signed and use generated
throwaway VPK keys under ignored `build/keys`; they are not production releases.

License, release version, remote repository, release credentials/signing policy
and external publication remain release-owner decisions. Local staging POMs must
not be mistaken for approved production publication metadata.

See [CANDIDATE.md](CANDIDATE.md) for the owner-decision checklist and read-only
candidate audit. It checks local artifacts, metadata and hashes without enabling
remote writes; repository-specific documentation and signing remain explicit gates.

Verified 2026-09-23 with Gradle 8.13, JDK 21, SDK 36: default `0.1.0-dev`
publication/consumer and the complete script with coordinated version
`0.1.0-staging-test` both passed. The latter used fresh repository
`/tmp/paravoid-local-publication.Kis5Iu`; log `/tmp/paravoid-publication-script.log`.
This establishes marker resolution, version alignment, transitive AAR/JAR metadata
and normal/shell/VPK builds, not device execution or remote publishing acceptance.

The combined regression run on 2026-09-23 passed 85 plugin, 10 Hilt, 9 Work,
32 contract and 17 runtime tests (153 total, no failures/errors/skips), plus
lifecycle and delivery host suites. Logs: `/tmp/paravoid-release-regression.log`,
`/tmp/paravoid-release-lifecycle.log`, `/tmp/paravoid-release-delivery.log`.
Source-copy TestKit fixtures now include the shared publication script; Hilt/Work
fixtures also copy current contract/delivery sources. The first broad run exposed
those missing fixture inputs; results above are the corrected full rerun.
This is not the remaining final device/security/real-app acceptance matrix.
The checked-in `regression.sh` itself also passed end-to-end, including Android
source compilation (`/tmp/paravoid-release-bundle.log`).

The 2026-09-23 finishing rerun passed 159 JUnit tests (86 plugin, 10 Hilt,
9 Work, 33 contract and 21 runtime), all lifecycle/delivery host suites and Android
delivery compilation: `/tmp/paravoid-final-regression.log`. The contract additions
include real final-rename IO failure after producing/verifying a VPK: a nonempty
destination directory and its sentinel survive, and no temporary VPK remains.
This supplements pre-publication validation-failure tests; it does not promise a
transaction across every Gradle report/metadata output. Consume build artifacts
only after the full task succeeds and validate their signed identities before
distribution. Independent security/physical/real-app gates remain separate.

The five-track integration rerun at production/tooling `1ef1ed4` passed the entire
updated `regression.sh`: 161 JUnit tests (88 plugin, 10 Hilt, 9 Work, 33 contract,
21 runtime), lifecycle/delivery host suites, Android source compilation, nine
physical-runner safety/controls-routing tests and eight local-candidate tests.
Log: `/tmp/paravoid-five-tracks-final-regression.log`. This includes the signing
entry bound and controls launcher implementation, but not physical execution or
authenticated Pezzottify workflows. Later documentation-only commits do not alter
these tested surfaces.

The complete bundle passed again after the controls observer fix (`72f5816`),
including its deterministic lifecycle-ordering test:
`/tmp/paravoid-five-tracks-observer-regression.log`. JUnit count remains 161;
the additional assertions live in the separate delivery host suite.
