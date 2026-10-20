# Changelog

This file records changes that downstream Android apps may need to act on when
updating their Paravoid plugin and runtime. Released versions are listed newest
first. The published modules share one version. Entries under **Unreleased**
are development changes and are not an available Maven release.
Add a concise Unreleased entry for every downstream-visible change. Before a
version tag, move those entries into a dated `## [VERSION] - YYYY-MM-DD` section;
each release must include a `### Migration` section with concrete app action or
"No app changes required." Do not rewrite notes for an already tagged version.
No public Paravoid version has been released yet. The first release will move
these entries into a dated version section and document its exact upgrade path.

## [Unreleased]

### Added

- Optional DVPK delivery in the built-in HTTP v1 updater, with bounded streaming
  reconstruction, authenticated base/patch/target hashes and full-VPK fallback.
  A new shell APK is required to enable it. Distributors negotiate discovery
  support and generate deltas from archived signed VPKs extracted from uploaded
  embedded-payload shell APKs. App publishers continue to upload APKs only.
  See [wire format and distributor responsibilities](DVPK.md).

- Opt-in authenticated local update triggers let a distributor such as LelloStore
  share one server connection across installed complete shells. The new
  `paravoid-update-ipc` module provides the Binder contract; shells verify the
  caller's package and signing certificate before scheduling a signed update
  check. Each app's check, download and restart preferences still apply, and no
  per-app WebSocket is required. See [configuration and protocol](delivery/UPDATES.md#distributor-triggered-local-checks).

- Opt-in complete-profile crash recovery in a separate shell process, durable
  next-launch routing, crash details and explicit repair download/restart. Choose
  the default updater or a shell-packaged code-only provider through the new
  `paravoid-recovery-api` module. See [integration guidance](docs/crash-recovery.md).

- Normal APK/AAB packaging and complete shell APK plus signed VPK packaging for
  one downstream Android app project. Complete shells verify updates, stage them
  for a coordinated cold start and provide shell-owned recovery controls.
- Optional Hilt and WorkManager integration modules for supported app setups.
- Complete shells expose `ParavoidUpdates.get()` so apps can observe verified
  update offers and staged releases, render their own UI, and trigger update
  controls.
- Experimental `minifyPayload` support shrinks and obfuscates payload DEX and
  writes a mapping file. Apps can supply `payloadProguardFiles` for code reached
  through reflection or serialization.

### Changed

- Payload R8 now automatically consumes transitive AAR/JAR consumer ProGuard
  rules, selects version-targeted rules for its actual SDK R8, and records them
  in `payload-consumer-rules.pro`. Manifest metadata referring to payload classes
  is retained, including Startup initializers. App-specific reflection and
  serialization still require app rules. Rebuild minified payloads and validate
  upgrades with existing user data; archive the consumer rules with the mapping.

- The extra **App updates** launcher icon defaults to off. Set
  `paravoid { controlsLauncher = true }` to keep that separate entry.

### Fixed

- A downloaded payload on its first-run trial is no longer quarantined because its
  app process was killed during startup (a swipe, a force stop, an OEM task killer,
  low memory). The runtime records each trial start's process and, at the next cold
  start, reads Android's exit reason for it: kills no longer count, while crashes,
  native crashes, ANRs and unknown ends keep the existing two-attempt budget (eight
  consecutive kills still pause the trial, as a backstop). Previously two killed
  starts silently quarantined a healthy payload.
- A quarantined payload with nothing newer to replace it now opens recovery with the
  quarantine's cause (unfinished starts and how they ended, or failed integrity)
  as the crash record. The runtime no longer stages the shell's embedded payload
  below the replay floor for it, which failed with `Lifecycle admission: REPLAY`
  and replaced the real cause in recovery. A higher embedded payload (repair APK)
  or a byte repair of the same release still replaces it.
- Embedded-bootstrap shells no longer get stuck in recovery with
  `Lifecycle admission: REPLAY` when an update check runs before the app's first
  launch. The check (on install, boot, or a distributor trigger) observed a newer
  head, which raised the payload floor above the embedded payload; every launch then
  failed staging it, **Retry app** had no paused payload to retry, and **Update**
  failed with `UNAVAILABLE` because the contract had never bootstrapped. Head
  observation is now gated like update writes until bootstrap. If an embedded payload
  is still below the floor (a floor raised by an older runtime or under a previous
  contract), recovery explains it and asks for an update, and checks and downloads
  for the installed contract are allowed so **Check again**, **Update** and
  **Restart** recover. Shells stuck this way need a new shell APK (an embedded payload
  at or above the floor, or this runtime); no payload can repair them.
- Crash records written before the installed shell APK's last update are marked
  handled when it starts, so installing a repair shell over a stuck one launches
  the app instead of the old recovery screen.
- A selected payload that the installed shell contract cannot load is paused as
  incompatible (shown in recovery) instead of as failed integrity.
- Crash records about another payload are marked handled once a different payload
  starts healthy, so a device repaired by a new shell APK no longer shows a stale
  "App recovery" record in its update controls.
- The selected release, quarantined or not, is no longer reported as an available
  update (`ParavoidUpdates` `available`, recovery's "Update available: N") and is
  not downloaded again, except to repair a selected release with corrupt files.
- The selection journal format is now version 3; version 2 journals are read and
  upgraded on the next write. Shells built with this runtime cannot be replaced by
  an older shell build without clearing app data (Android forbids that downgrade
  anyway). Rebuild and install the shell APK to receive these fixes: the runtime is
  part of the shell, so no payload can deliver them.

- Embedded shells now allow signed recovery downloads when the selected payload is
  quarantined, even if a replaced shell has not bootstrapped and its embedded
  fallback is below the persisted replay floor. Fresh installs still defer update
  writers until bootstrap. Rebuild and install the shell APK to receive this fix.

- Complete shells no longer fail or hang their first launch after an APK update
  that changes the shell contract. The update process, started by
  `MY_PACKAGE_REPLACED`, could reserve the update space for a whole payload
  download while the main process needed it to stage the embedded payload.
  Embedded-bootstrap shells now record when the main process has acquired a
  release for the installed contract; until then, update downloads and staging
  return `UNAVAILABLE` and retry later. Startup also waits up to two seconds
  for any other writer and retries loading before staging its own copy. Shells
  must be rebuilt to pick this up.

- A shell update that changes the installed update schedule now reaches existing
  installs. The runtime used to persist the whole effective schedule on first
  start and prefer it over the shell policy from then on, so changes such as
  enabling background checks (needed for local update hints) or lengthening
  the check interval only applied to fresh installs. Only explicit runtime
  choices (`preferences(...)` and `schedule(...)`) are now durable; everything
  else follows the installed policy on every start. Existing update state adopts
  the current policy once, keeping pending work, retries and offers; runtime
  update preference choices made before this change are not retained. Shells
  must be rebuilt to pick this up.

### Migration

- Local update triggers are disabled by default. To adopt them, upgrade the
  Paravoid plugin/runtime, configure `updates.localTriggers.trustedCallers` with
  the distributor package and certificate SHA-256 fingerprints, and build and
  distribute a new shell APK. A VPK alone cannot enable the endpoint or change
  its trusted callers. The distributor must also implement the relay; LelloStore
  forwards socket/reconnect events and polling/manual checks. Enable automatic
  checks and, if desired, downloads in each app. Android scheduling and hint
  throttling mean a trigger does not guarantee an immediate download or restart.

- Crash recovery is disabled by default. Enabling it or changing a custom provider
  requires a new shell APK; compatible app repairs can then ship as VPKs.

- First-time adopters should follow the complete-profile integration and
  compatibility requirements in the README and V1 contract. Complete packaging
  targets Android API 30+ and uses a standalone shell APK.
- Apps integrating the new update state can subscribe in a screen's `onStart`
  and close the subscription in `onStop`. See the README example.
- Existing complete shells that relied on the separate launcher icon should set
  `controlsLauncher = true` when upgrading. Payload minification remains opt-in;
  validate an actual minified payload with saved app data before publishing it.
