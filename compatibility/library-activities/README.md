# Library Activity packaging

Uses the actual Pezzottify dependency versions: AppAuth 0.11.1 and Androidoscopy
sdk-ui 2.0.2, with AGP 8.13.2, compile/target 36 and minSdk 30. Androidoscopy must
already be published to Maven local, as required by Pezzottify's build setup.
There are no copied/stubbed library Activities and no instrumentation dependency.

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew -p compatibility/library-activities \
  assembleNormalDebug assembleParavoidAndroidDebug lintNormalDebug lintParavoidAndroidDebug
python3 compatibility/library-activities/check.py
```

Both builds/lint and artifact checks pass. The shell retains the actual merged
declarations for AppAuth's `AuthorizationManagementActivity` and Androidoscopy's
`DashboardActivity` / `SessionActivity`, including themes, exported flags and the
AppAuth launch mode. Their implementation classes are absent from shell DEX and
present in the payload. Normal mode contains their ordinary implementations.
The fixture removes AppAuth's redirect receiver Activity, matching Pezzottify;
the management Activity remains. Removing the redirect entry here does not test
Pezzottify's app-owned OAuth callback handling.

The plugin now discovers the single MAIN/LAUNCHER Activity rather than assuming
the first manifest Activity is the launcher. All declared payload Activities receive
the loader/configuration-context/saved-state hooks. Shared inherited callbacks are
instrumented once per method across the Activity set. The 39 core plugin tests
include manifest attribute preservation, dependency-contributed Activity packaging,
all secondary-Activity hooks, shared-base deduplication, and launcher/alias guards.

## Requirements and limitations

- Exactly one MAIN/LAUNCHER filter is still required. Multiple launchers, aliases
  and TV-only LEANBACK entry are not supported by this change.
- Activity declarations and pinned resources remain shell-owned; class code stays
  in the payload. Adding/removing/renaming an Activity or changing its installed
  configuration requires a shell update. Automated shell-baseline diff/rejection
  is still future work, not implemented by accepting extra Activities.
- These are build/bytecode/artifact checks, **not device verification of OAuth or
  Androidoscopy UI/session flows**. Cold external entry, multi-Activity restoration,
  result delivery and real SDK initialization still need device coverage.
- Resources/native libraries still use the existing installed-APK path. This is
  not complete resource packaging or downloaded payload support.
- This fixture does not establish AGP 9 support or a working Pezzottify integration.
  Pezzottify's dedicated `codex/paravoid-integration` worktree was rebased onto
  `dev` at `0b258266` after the player extraction; its app has not been modified
  to apply Paravoid yet. Hilt/Application setup, SDK minima and build-variant
  configuration remain integration work. Do not add duplicate manifest entries:
  the libraries already contribute the declarations.
