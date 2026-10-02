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

- The extra **App updates** launcher icon defaults to off. Set
  `paravoid { controlsLauncher = true }` to keep that separate entry.

### Fixed

- Complete shells no longer fail or hang their first launch after an APK update
  that changes the shell contract. The update process, started by
  `MY_PACKAGE_REPLACED`, could reserve the update space for a whole payload
  download while the main process needed it to stage the embedded payload.
  Embedded-bootstrap shells now record when the main process has acquired a
  release for the installed contract; until then, update downloads and staging
  return `UNAVAILABLE` and retry later. Startup also waits up to two seconds
  for any other writer and retries loading before staging its own copy. Shells
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
