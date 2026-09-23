# Isolated real-app acceptance

This is **logged-out**, disposable-emulator acceptance, not authenticated app or
physical-device approval. Never point these tests at a phone, a user's installed
app/data, or production account credentials.

The experiment uses separate worktrees. Existing Pezzottify `dev` and
`codex/paravoid-integration` worktrees are not edited. The downstream fixture
branch `codex/paravoid-release-realapp` starts at `83cb61df` and adds `6ade94fb`:
opt-in loopback public delivery plus a generation marker and deliberate startup
fault. This branch is not a proposal to merge fault injection into the real app.
The pinned assistant checkout is `87a499561a4db146d75051c34d46f89f4dac9e85`.
Androidoscopy dependencies must already be prepared in local Maven as described
by the downstream repository. Gradle 9.1/AGP 9 are used for this real app.

```sh
export ANDROID_HOME=/home/lelloman/Android/Sdk
export GRADLE_RO_DEP_CACHE=/home/lelloman/.gradle/caches
export PEZZOTTIFY_ANDROID=/tmp/pezzottify-release-realapp/android
export ASSISTANT_CHECKOUT=/tmp/assistant-release-realapp
export PARAVOID_GRADLE=/path/to/gradle-9.1.0/bin/gradle
export PARAVOID_GRADLE_USER_HOME=/tmp/paravoid-realapp-gradle
export REALAPP_CASES=/tmp/paravoid-realapp-cases
export REALAPP_KEYS=/tmp/paravoid-release-realapp-keys
bash release-tests/pezzottify-build.sh
python3 release-tests/pezzottify-device.py \
  --serial emulator-5586 --avd Restart30 \
  --a "$REALAPP_CASES/A" --b "$REALAPP_CASES/B" \
  --broken "$REALAPP_CASES/broken" --repair "$REALAPP_CASES/repair" \
  --keys "$REALAPP_KEYS"
```

Build outputs must go to a **new** directory. Keys are throwaway fixture keys,
never production publisher credentials. The script refuses to overwrite an
existing app installation; deliberately provision a fresh disposable emulator.
The shell uses `http://127.0.0.1:19165/`, public updates and debug HTTP opt-in.
Only one shell APK is installed. A's accepted baseline governs B, broken and
repair builds; updates download through the production HTTP/verifier/lifecycle
path. Controls use the installed public updates alias and actual confirmation UI.
No verifier or runtime journal is replaced by the harness.

The incompatible case re-signs a VPK with a different shell-contract identity
using the fixture release key, then serves it with a valid head for the installed
shell. This tests installed admission of a signed incompatible release, **not**
the producer's manifest-change baseline gate (which has separate plugin tests).

Assertions include actual logged-out Compose UI, payload Application generation
markers, production healthy/pending/quarantine state, unchanged installed APK
path, downloaded B activation, signed incompatible refusal, broken startup and
forward repair. Room checks are SQLite integrity and retained Room identity for
`StaticsDb` and `user_content`; these are **not** proof of populated account data
or arbitrary migrations. A helper reads copied databases after stopping the app;
it does not rewrite those databases or mutate the lifecycle journal.

Remaining coordinated workflows require an approved test backend/OIDC client and
account: token exchange/refresh and retained login, browsing populated content,
playback/background sync, Androidoscopy screens, and assistant JNI execution.
The packaged native library and app startup alone do not prove JNI calls work.
The experiment's shell-only Room direct-boot override remains unsuitable for
applications requiring pre-unlock payload execution. Existing callback URI
registration and separate `.paravoid` app identity requirements still apply.

## Evidence

Initial production `3b163a1` build passed normal and complete shell packaging in
`/tmp/paravoid-realapp-build.log`. The normal app's logged-out Compose login UI
was observed on API 30. Final update artifacts and matrix results must be recorded
separately; this initial diagnostic build is not the fixed-shell acceptance run.
