#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
# Use an explicit existing Gradle binary in offline environments; otherwise wrapper.
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
paravoid_repository=$(mktemp -d /tmp/paravoid-local-publication.XXXXXX)
echo "Local staging repository: $paravoid_repository"
common=(--no-daemon --max-workers=2 --console=plain "-PparavoidRepository=$paravoid_repository")
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then
    common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME")
fi
if [[ "${PARAVOID_OFFLINE:-false}" == true ]]; then common+=(--offline); fi
if [[ -n "${PARAVOID_VERSION:-}" ]]; then common+=("-PparavoidVersion=$PARAVOID_VERSION"); fi
if [[ -n "${PARAVOID_LICENSE_NAME:-}" ]]; then common+=("-PparavoidLicenseName=$PARAVOID_LICENSE_NAME"); fi
if [[ -n "${PARAVOID_LICENSE_URL:-}" ]]; then common+=("-PparavoidLicenseUrl=$PARAVOID_LICENSE_URL"); fi
"$paravoid_gradle" "${common[@]}" \
    :paravoid-recovery-api:publishAllPublicationsToLocalStagingRepository \
    :paravoid-api:publishAllPublicationsToLocalStagingRepository \
    :paravoid-contract:publishAllPublicationsToLocalStagingRepository \
    :paravoid-runtime:publishAllPublicationsToLocalStagingRepository \
    :paravoid-gradle-plugin:publishAllPublicationsToLocalStagingRepository \
    :paravoid-hilt:publishAllPublicationsToLocalStagingRepository \
    :paravoid-work:publishAllPublicationsToLocalStagingRepository
python3 release-tests/consumer/prepare.py
"$paravoid_gradle" "${common[@]}" -p release-tests/consumer \
    assembleNormalDebug assembleParavoidAndroidDebug packageParavoidAndroidDebugParavoidVpk
echo "PASS local publication and standalone consumer build; repository retained at $paravoid_repository"
