#!/usr/bin/env bash
# Builds artifacts into Maven local for JitPack to collect. No push/upload/tag.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
paravoid_version=${PARAVOID_VERSION:-${VERSION:-}}
paravoid_group=${PARAVOID_GROUP:-${GROUP:-com.github.lelloman}.${ARTIFACT:-paravoid-android}}
[[ "$paravoid_version" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]] || {
    echo 'Set VERSION (JitPack tag/commit) or PARAVOID_VERSION to a safe version.' >&2; exit 1;
}
[[ "$paravoid_group" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_-]+)+$ ]] || {
    echo 'Invalid Maven group.' >&2; exit 1;
}
# A version tag is a public release: its downstream notes must ship in that commit.
if [[ "$paravoid_version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    python3 release-tests/changes.py --verify "$paravoid_version"
fi
paravoid_gradle=${PARAVOID_GRADLE:-./gradlew}
common=(--no-daemon --max-workers=2 --console=plain
    "-PparavoidGroup=$paravoid_group" "-PparavoidVersion=$paravoid_version")
if [[ -n "${PARAVOID_GRADLE_USER_HOME:-}" ]]; then common+=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME"); fi
if [[ "${PARAVOID_OFFLINE:-false}" == true ]]; then common+=(--offline); fi
if [[ -n "${PARAVOID_MAVEN_LOCAL:-}" ]]; then common+=("-Dmaven.repo.local=$PARAVOID_MAVEN_LOCAL"); fi
if [[ -n "${PARAVOID_LICENSE_NAME:-}" ]]; then common+=("-PparavoidLicenseName=$PARAVOID_LICENSE_NAME"); fi
if [[ -n "${PARAVOID_LICENSE_URL:-}" ]]; then common+=("-PparavoidLicenseUrl=$PARAVOID_LICENSE_URL"); fi
"$paravoid_gradle" "${common[@]}" \
    :paravoid-api:publishToMavenLocal :paravoid-contract:publishToMavenLocal \
    :paravoid-runtime:publishToMavenLocal :paravoid-gradle-plugin:publishToMavenLocal \
    :paravoid-hilt:publishToMavenLocal :paravoid-work:publishToMavenLocal
