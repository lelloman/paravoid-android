#!/usr/bin/env bash
# Build only in a disposable downstream worktree with the documented fixture patch.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
: "${PEZZOTTIFY_ANDROID:?isolated downstream android directory required}"
: "${ASSISTANT_CHECKOUT:?pinned isolated assistant checkout required}"
: "${PARAVOID_GRADLE:?Gradle 9.1 executable required}"
: "${PARAVOID_GRADLE_USER_HOME:?separate Gradle home required}"
: "${REALAPP_CASES:?new immutable artifact output directory required}"
: "${REALAPP_KEYS:?test signing key directory required}"
[[ ! -e "$REALAPP_CASES" ]] || { echo 'Artifact directory already exists; use a new one' >&2; exit 1; }
mkdir -p "$REALAPP_CASES"
python3 "$repo/packaging-tests/prepare-keys.py" \
  --application-id com.lelloman.pezzottify.android.paravoid --output "$REALAPP_KEYS"
common=(--gradle-user-home "$PARAVOID_GRADLE_USER_HOME" --offline --no-daemon --no-parallel --max-workers=2
  "-PparavoidCheckout=$repo" "-PassistantCheckout=$ASSISTANT_CHECKOUT" -PassistantAbis=x86_64
  -PparavoidComplete=true -PparavoidAcceptance=true
  "-PparavoidTrustPolicy=$REALAPP_KEYS/trust.json" "-PparavoidReleaseKey=$REALAPP_KEYS/release.der")
cd "$PEZZOTTIFY_ANDROID"
output=app/build/outputs/paravoid/paravoidAndroidPhoneDebug
for generation in A B broken repair; do
  # Version 3 belongs to the signed incompatible head, even though its VPK fails.
  case "$generation" in A) version=1 ;; B) version=2 ;; broken) version=4 ;; repair) version=5 ;; esac
  baseline=()
  if [[ "$generation" != A ]]; then baseline=("-PparavoidBaseline=$REALAPP_CASES/accepted"); fi
  "$PARAVOID_GRADLE" "${common[@]}" "${baseline[@]}" \
    "-PparavoidPayloadVersion=$version" "-PparavoidAcceptanceGeneration=$generation" \
    :app:assembleParavoidAndroidPhoneDebug > "$REALAPP_CASES/$generation-build.log" 2>&1
  mkdir "$REALAPP_CASES/.$generation-staging"
  cp -a "$output/." "$REALAPP_CASES/.$generation-staging/"
  if [[ "$generation" == A ]]; then
    mkdir -p "$REALAPP_CASES/accepted/paravoidAndroidPhoneDebug"
    cp -a "$output/baseline-candidate/." "$REALAPP_CASES/accepted/paravoidAndroidPhoneDebug/"
  fi
  mv "$REALAPP_CASES/.$generation-staging" "$REALAPP_CASES/$generation"
  echo "Built $generation/$version"
done
