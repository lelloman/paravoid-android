# Changelog

This file records changes that downstream Android apps may need to act on when
updating their Paravoid plugin and runtime. Released versions are listed newest
first. The seven published modules share one version. Entries under **Unreleased**
are development changes and are not an available Maven release.
Add a concise Unreleased entry for every downstream-visible change. Before a
version tag, move those entries into a dated `## [VERSION] - YYYY-MM-DD` section;
each release must include a `### Migration` section with concrete app action or
"No app changes required." Do not rewrite notes for an already tagged version.
No public Paravoid version has been released yet. The first release will move
these entries into a dated version section and document its exact upgrade path.

## [Unreleased]

### Added

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

### Migration

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
