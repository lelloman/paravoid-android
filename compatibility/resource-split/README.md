# Same-package resource split proof

First implementation slice of [the packaging contract](../../PACKAGING.md).
This is a bounded fixture, not automatic resource splitting for downstream apps.

## Linker proof

```sh
python3 compatibility/resource-split/pack-resources.py "$ANDROID_HOME" /tmp/paravoid-resource-split-link
python3 -m unittest discover -s compatibility/resource-split -p 'test_*.py'
```

Requires Python 3.9+, Build Tools 35.0.0 and platform android-36. AAPT2 compiles
an Android static resource library containing a string, custom attribute and
styleable, then links it with app resources into one `0x7f` package. Generated
app/library R classes come from this complete link. Version B consumes A's emitted
stable IDs, changes a string/color, adds a string and removes another.

A second link uses the same ledger but only pinned sources. Its resource table
has the shell label, theme and theme color, with their original numeric IDs;
movable values are absent. B retains the removed symbol's reservation in its ID
file but has no resource value for it. The newly added name does not reuse that ID.

These linker assertions and five host validator tests pass. This alone does not
prove runtime lookup precedence, early loading, or automatic pruning of an
arbitrary AGP resource graph. Pinned input sources are deliberately hand-selected
to isolate the linking question. Full contract-gate coverage remains pending.

## Device proof

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/resource-split/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/resource-split/check.sh --device
```

Use a dedicated unlocked emulator. Both packaging variants intentionally share
`com.lelloman.paravoidcompat.resourcesplit`; the driver installs and clears this
disposable package once per mode, leaves the last APK installed, and force-stops it
afterward. It does not clear data or reinstall between resource selections.

API **30 and 36.1**, x86_64/debug: all **12 device stages pass on each API**,
six per packaging mode:

- `none`: no pack attached; shell label/theme work, movable values are absent.
- A → B → A: new PID each time; installed APK path/hash unchanged. Verify XML
  inflation, pinned and payload themes, color changes, Italian configuration
  context, generated app/library R IDs and library styleable lookup. Removed
  resource values do not fall back to A or installed resources.
- Tampered and writable packs are rejected before a new successful report.

Every successful startup checks provider lookup before Application `onCreate` and
Application `onCreate` lookup. In shell mode, the transformed Application also
reads a payload resource in its constructor. Normal Application constructors do
not yet have an attached Context, so this constructor check is shell-specific.
Artifact inspection checks that both installed resource tables are pinned-only
and that movable compiled XML is not in the APK. Both builds/lint pass; eleven
host tests exercise ledger invariants and device-report acceptance/rejection.
After cleaning the fixture and its isolated runtime outputs, a full rebuild and
API 30 rerun also pass. Both resource APKs and A/B ID ledgers have identical SHA-256
digests before and after the clean build. A no-change incremental build passed too;
this is not yet evidence for incremental correctness after arbitrary source edits.

## Isolation and remaining work

The fixture runtime copies production runtime sources and inserts exactly one
early call in `ShellApplication.attachBaseContext`, before payload classes are
loaded/constructed. It fails its build if that source anchor changes. No production
runtime/plugin is modified. Normal mode calls the same helper from Application
attachment. That override is fixture scaffolding, not a new downstream requirement
or a claim that arbitrary Application attachment overrides are transformed.

The helper hashes the same read-only descriptor used for resource loading against
build-pinned A/B digests. Packs are staged privately with adb, not downloaded. The
`none` selector is a negative control, not a production partial-startup policy.
Both modes use external resources here to compare ordinary versus payload code
loading; this is not the future normal mode's conventional self-contained package.

Generated app R uses a fixture-only Java namespace (`.linked`) to avoid conflicting
with AGP's pinned-only R class. Library resources are a real AAPT2 static library,
not yet an ordinary AGP Android library dependency with automatic split ownership.
Pinned inputs are hand-selected, and the ledger is seeded afresh from A for this
bounded A/B test. No persistent release-ledger lifecycle is implemented yet.

Still pending: arbitrary merged resource-graph pruning;
ordinary app/library R integration; Compose, notification/other system-consumed
resources, Activity recreation and saved-state restoration, named workers; semantic
pinned-content/manifest contract rejection; coherent code+resource version changes;
native/asset/Java-resource packaging; complete-payload format/signing; source-change
incremental-build correctness and wider toolchains/ABIs. Repeated cold launches
here are not a substitute for process-death saved-state restoration tests.
