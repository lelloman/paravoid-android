# Production embedded-resource shell gate

```sh
ANDROID_HOME=/path/to/sdk python3 compatibility/automatic-resources/production-check.py
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 \
  python3 compatibility/automatic-resources/production-check.py --device
# Reuse the built production artifacts on a second dedicated emulator:
ANDROID_SERIAL=emulator-5556 python3 compatibility/automatic-resources/production-check.py --device-only
```

The original [fixture requirements](README.md) apply. This script selects the real
`paravoid-runtime` project, not `fixture-runtime`, and invokes
`package<Variant>ParavoidResourceShell`. No Python APK repacking, injected loader,
trusted-hash Java generation or local resource-selector file is used. APK signatures
and resource identities are generated and verified by the production task.

## Evidence — 2026-09-21

22/22 stages passed on each of the API 30 and API 36.1 x86_64 emulators identified
in [VALIDATION.md](VALIDATION.md):

- Four normal-A control stages.
- A, B, A installed APK versions: early Application constructor/provider/onCreate
  access in main and named worker, generated library R/styleables, XML/theme,
  resources/assets, removals/additions and a preserved data marker (three stages).
- Each installed version: explicit recreation, night, day and a cold restart that
  reuses the private immutable cache (twelve stages).
- Same-length cache corruption and writable cache rejection before any fresh
  successful Activity report; then restoration of the exact original bytes and
  successful startup (three stages).

Both normal/shell lint tasks and artifact checks passed. The installed table has
only the three pinned entries, no movable `res/` or ordinary asset fallback, and
shell DEX contains `EmbeddedResources` but not fixture `SplitResources`. JSON
evidence is written under `build/production/evidence-<serial>.json`.

Standalone runtime-library lint still reports the existing API-28
`AppComponentFactory` references against that library's declared minSdk 26.
This is distinct from the passing API-30 application-level lint checks; the new
resource loader isolates its API-30 references. No API-26 shell support is claimed.
Run the plugin TestKit suite and other repository Gradle builds sequentially:
they share the plugin's compiled output directory, and concurrent recompilation
can cause missing-plugin-class failures unrelated to packaging behavior.

On 2026-09-22 the signed production APK builds/artifact checks and app-level lint
passed again, along with 64 plugin tests (fresh isolated execution), 16 runtime
unit tests and 13 Python fixture tests. Device tests were not repeated that day;
the API 30/36.1 results above are the recorded 2026-09-21 runs.

## Interpretation and limits

These A/B/A transitions are **APK replacement**, not updates against a fixed shell.
They test cache identity/invalidation and persistent app data across installed
generations. The original fixture remains the fixed-shell resource-switching proof.
The driver deliberately repairs cache bytes for a negative-control recovery test;
the runtime does not provide that repair mechanism to users yet.

This implements production assembly/loading for an explicit embedded-resource APK
stage, not the full v1 packaging contract. Native relocation is now covered by the
separate [JNI payload gate](../jni/PAYLOAD.md);
standalone VPK signing, external activation/leases, empty-shell adapters, cache
retention and recovery UI are unfinished. No ARM64, release-device, R8, direct-boot,
isolated-process or broader AGP compatibility claim follows from these runs.

## Java-resource relocation — 2026-09-22

The extended production gate passed **25/25 stages on both API 30 and API 36.1**.
All positive stages now check Java-resource lookup from Application constructors,
providers, Application.onCreate, Activities and the named worker. App/library
service descriptors are merged by AGP and discovered with ServiceLoader; class
resource streams and enumeration, one-result lookup, excluded resources and paths
containing spaces, `#` and `%` are verified. A/B APKs carry different Java-resource
bytes, and successful shell lookups must point into the private Java-resource JAR,
not the installed APK. The extra three stages reject same-length JAR corruption,
reject writable JARs, and verify test-only byte repair.

64 plugin tests and 17 runtime tests passed, including final-APK merge/exclude
preservation and escaped JAR URL unit coverage. Normal/shell lint passed. The
AGP 8.13.2 public scoped JAVA_RES inputs were empty in this pipeline, so production
selection uses the final standalone APK's non-Android entries rather than private
intermediate paths. Reserved Android metadata/signature entries are not relocated.

The normal control exposed a Binder-thread assumption: default ServiceLoader sees
no app providers on pre-existing Binder threads. Startup tests retain default
discovery; Binder-call tests explicitly supply the defining loader in both modes.
No global Binder-thread mutation is introduced by Paravoid.
