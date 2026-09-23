# Local release staging

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

The repository is retained at the printed `/tmp/paravoid-local-publication.*`
path for inspection. Only local-file staging is configured; no remote upload,
credentials, Maven Central/Plugin Portal approval or artifact signing is implied.
Android API/runtime release AARs, contract/plugin JARs, sources, dependency metadata
and plugin markers are published. Consumer APKs are debug-signed and use generated
throwaway VPK keys under ignored `build/keys`; they are not production releases.

License, release version, remote repository, release credentials/signing policy
and external publication remain release-owner decisions. Local staging POMs must
not be mistaken for approved production publication metadata.

Verified 2026-09-23 with Gradle 8.13, JDK 21, SDK 36: default `0.1.0-dev`
publication/consumer and the complete script with coordinated version
`0.1.0-staging-test` both passed. The latter used fresh repository
`/tmp/paravoid-local-publication.Kis5Iu`; log `/tmp/paravoid-publication-script.log`.
This establishes marker resolution, version alignment, transitive AAR/JAR metadata
and normal/shell/VPK builds, not device execution or remote publishing acceptance.
