# Automatically generated resource split — device gate

This fixture consumes the production plugin's `split<Variant>ParavoidResources`
outputs from ordinary Android app and library sources. It does not manually link
separate shell/payload resource folders. It tests the generated pair on-device;
it is **not the production full-VPK assembler or updater**.

```sh
ANDROID_HOME=/path/to/sdk bash compatibility/automatic-resources/check.sh
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 \
  bash compatibility/automatic-resources/check.sh --device
```

Use a dedicated, unlocked API 30+ emulator with English as its default locale.
The device gate installs/clears only `com.lelloman.paravoidcompat.automaticresources`,
changes day/night mode temporarily, and restores that mode afterward. Build tools
35.0.0, compile SDK 36, the repository's JDK/Gradle prerequisites and Python 3.9+
are required. A disposable test signing key is generated under ignored `build/`.
Do not run Python with assertions disabled (`-O`).

## What is exercised

- Real generated app/library R classes and library styleables, compiled XML layout,
  pinned and movable themes/colors, Italian and night configurations, app assets.
- Version A establishes the resource-ID ledger and resource-boundary baseline.
  B changes values/assets, adds `a_added` and removes `removed`; existing IDs remain
  stable and the generated shell resource archive must remain byte-identical.
- The normal APK is the control, with no dynamic resource loader. The shell uses
  the actual generated pinned-only table/files, without installed movable assets
  or a full-table fallback. Both use the same package ID (`0x7f`).
- Shell selection `none → A → B → A`, cold-starting both main and named-worker
  processes each time, with the installed APK path/hash and app data marker fixed.
  Removed resources must not fall back to A or to installed resources.
- Payload Application constructor, provider-before-Application-onCreate, Application
  onCreate and named-process provider access; Activity recreation, live day/night
  changes and configuration contexts. Recreation/configuration checks require the
  same main and worker processes to survive.
- Corrupt and writable resource archives are rejected by the fixture's early
  loader, before a fresh successful Activity report can be produced.

Each emulator run has 19 stages (4 normal, 13 successful shell, 2 rejected shell).
Detailed reports, including device fingerprint/API and per-stage observations, are
written to `build/validated/evidence-<serial>.json`. Host tests mutate probe evidence
to ensure assertions reject stale/wrong values and verify fixture ZIP assembly.

## Test-only scaffolding and remaining limits

`build-fixture.py` builds normal and shell variants, exports A's accepted baseline,
and invokes the production split tasks. It pins the generated A/B archive hashes
in fixture Java, rebuilds and asserts this did not change those archive bytes.
It replaces the original shell APK's resource table/files with the plugin-generated
shell container, removes movable installed assets, then aligns and debug-signs the
result. It does not implement resource selection, pruning or relinking itself.

The fixture runtime copies production runtime sources and inserts the existing
[`resource-split` early loader](../resource-split/README.md) before payload classes
are instantiated. Its shared source is fixture-only. Production runtime sources
and APK assembly are unchanged. Hash pinning here is a bounded test mechanism,
not the signed-VPK trust protocol.

Installed code and embedded payload DEX stay at A while resource archives change.
This proves resource-ID compatibility and lifecycle behavior, **not a coherent
code/resources/native update or production rollback**. Selection is staged locally
with every app process stopped; concurrent activation and downloaded updates are
not exercised. No network, updater UI, data migrations or protocol claims follow.

This gate does not cover Compose resource lookup, a system-consumed notification
resource, ARM64 hardware, release/R8, Java-resource/native relocation or the full
shell contract. Those remain separately gated in [PACKAGING.md](../../PACKAGING.md).
