# Track A finishing work

Baseline: `main` at `7425642`. Branch: `v1/packaging-finish`; worktree:
`/tmp/paravoid-v1-packaging-finish`.

Owns Gradle packaging, baseline/report outputs, signing/verification formats,
real-app/AGP integration and final cross-track integration. Track B owns `delivery/`;
Track C owns `paravoid-runtime/`, `lifecycle-tests/` and `compatibility/complete-v1/`.
Do not edit those trees or shared interfaces without coordinating changes.

Builds use `/tmp/paravoid-track-a-gradle` as a separate Gradle user home and
`GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches` for read-only dependency reuse.
Included projects must resolve inside this worktree. No phone, publishing or
remote push is authorized by the track assignment.

## Verified slices

- Added required root-level shell contract, resource ledger and JSON/text packaging
  reports, including ownership, pinned chains, public key IDs, limitations and
  baseline changes. Reports are generated before a compatibility gate fails.
- Added `payload.sha256`; retained the prior filename as an identical alias.
- TestKit tests pass for automatic embedded/empty assembly and signed VPK baseline
  updates, now also asserting report content, public output locations, compatibility
  failures and unchanged normal APKs. Existing shell-contract unit tests pass.

This evidence is build-level, not installed-device acceptance. Remaining Track A
work includes complete-profile boundary tests, real-app AGP integration, publishing
artifact correctness and cross-track acceptance after B/C handoff.

## Pezzottify build experiment

The isolated app worktree is `/tmp/pezzottify-paravoid-v1-track-a`, branch
`codex/paravoid-v1-packaging`, based on integration commit `152445ac`. Its optional
`paravoidComplete` property enables the complete profile without changing its normal
variants. It includes `paravoid-contract` from this checkout. The assistant source
is independently checked out at its pinned commit
`87a499561a4db146d75051c34d46f89f4dac9e85` in `/tmp/paravoid-track-a-assistant`.

Generate disposable RSA test keys (requires Python `cryptography`):

```sh
python3 packaging-tests/prepare-keys.py \
  --application-id com.lelloman.pezzottify.android.paravoid \
  --output /tmp/paravoid-track-a-pezzottify-keys
```

The helper reuses existing keys; never use this fixture for production signing.
It creates private files with mode 0600 and exports only public keys in `trust.json`.
Keep the generated directory outside Git. Re-running the helper was checked to
preserve the public trust policy.

From the isolated app's `android/`, use Gradle 9.1 with these arguments:

```sh
./gradlew --gradle-user-home /tmp/paravoid-track-a-gradle \
  -PparavoidCheckout=/tmp/paravoid-v1-packaging-finish \
  -PassistantCheckout=/tmp/paravoid-track-a-assistant \
  -PparavoidComplete=true \
  -PparavoidTrustPolicy=/tmp/paravoid-track-a-pezzottify-keys/trust.json \
  -PparavoidReleaseKey=/tmp/paravoid-track-a-pezzottify-keys/release.der \
  :app:assembleParavoidAndroidPhoneDebug --no-parallel --max-workers=2
```

Gradle 9's Groovy 4 exposed missing explicit `groovy.xml.XmlParser` imports in
two packaging tasks. The fix compiles under Gradle 9; Gradle 8 shell-contract and
resource-shell regression tests also pass. This is not device acceptance.

The real app also exposed AAPT-generated names such as
`drawable/$avd_hide_password__0`. Resource-ledger export, installed-policy decoding
and VPK admission now preserve `$` in linked names. Regression tests cover exact
stable-ID/tombstone preservation and continued rejection of path-like names;
resource-ledger tests and the shared contract suite pass.

The signed VPK then built successfully, but shell assembly rejected Room's
library-declared `androidx.room.MultiInstanceInvalidationService` because it is
`directBootAware=true`. The isolated app has no call enabling multi-instance
invalidation or device-protected storage. Its Paravoid-only manifest overlay
sets that service to `directBootAware=false`; normal packaging is unaffected.
This is an explicit app integration decision, not automatic removal of a library
capability by Paravoid. Apps that require pre-unlock payload execution remain
unsupported. Earlier actionable diagnostics for this condition remain a follow-up.

Final complete `assembleParavoidAndroidPhoneDebug` passed on AGP 9.0.0 / Gradle
9.1.0. APK v3 signature verification passed. The embedded VPK's SHA-256 equals
the standalone VPK and `payload.sha256`; the VPK is 86,047,312 bytes. The shell
contains the public policy, shell DEX and four ABI markers. This is a debug-key
signed, embedded-bootstrap, updates-disabled build, not a production release.
The build used the opt-in app configuration committed as `dd6fe7a9` plus the
Paravoid-only Room overlay. No APK was installed; runtime acceptance is pending
integration with Tracks B/C.
