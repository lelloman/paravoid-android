#!/usr/bin/env bash
# Emulates JitPack coordinates locally; does not claim a remote JitPack build.
set -euo pipefail
cd "$(dirname "$0")/.."
export PARAVOID_MAVEN_LOCAL
PARAVOID_MAVEN_LOCAL=$(mktemp -d /tmp/paravoid-jitpack-maven.XXXXXX)
export PARAVOID_GROUP=com.github.lelloman.paravoid-android
export PARAVOID_VERSION=${PARAVOID_VERSION:-jitpack-local-test}
bash release-tests/jitpack-install.sh
python3 release-tests/consumer/prepare.py
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
common=(--no-daemon --max-workers=2 --console=plain
    "-PparavoidGroup=$PARAVOID_GROUP" "-PparavoidVersion=$PARAVOID_VERSION"
    "-PparavoidRepository=$PARAVOID_MAVEN_LOCAL")
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME"); fi
if [[ "${PARAVOID_OFFLINE:-false}" == true ]]; then common+=(--offline); fi
"$paravoid_gradle" "${common[@]}" -p release-tests/consumer \
    assembleNormalDebug assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk
echo "PASS JitPack-coordinate local publication/consumer; retained at $PARAVOID_MAVEN_LOCAL"
