# Room migration across two code payload versions

Pinned Room runtime/compiler 2.6.1, Java annotation processing, compile/target SDK
36 and min SDK 28. Both versions define the **same database and generated DAO
class names**. Their schema snapshots are checked in under `schemas/`; the build
driver compares the generated schemas to those snapshots.

```sh
ANDROID_HOME=/path/to/Android/Sdk python3 compatibility/migrations/build-check.py
ANDROID_SERIAL=emulator-5584 python3 compatibility/migrations/device-check.py
python3 -m unittest discover -s compatibility/migrations -p test_device_check.py
```

Use Python 3.9+, adb on PATH and a dedicated unlocked emulator. The device driver
installs and clears only the two migration fixture packages, once before seeding.
It does not uninstall or clear data between stages. Force-stop separates cold
code activations; this is not a mid-transaction crash or background-launch test.

## What changes between stages

The **normal control** uses debug APK updates (`install -r -d`) with the same app
ID/signing key and preserved app data, including a debug version-code downgrade.

The **shell experiment** installs one APK containing both prebuilt payload bundles.
A private `files/migration-version` selector chooses v1 or v2 on the next process
start. The driver checks the installed APK path and SHA-256 throughout: no shell
reinstall or APK modification occurs between payload versions.

This selection is **fixture-only scaffolding**. `fixture-runtime` generates a copy
of the production runtime sources and replaces exactly one bundle-opening
expression in ShellApplication. It fails the build if that source anchor changes.
All subsequent bundle validation, DEX loading and component creation use the
production implementation. The selector accepts only 1/2 and opens assets inside
the already installed APK; it accepts no external DEX, network source or arbitrary
file path. Production runtime/plugin source and public APIs are unchanged.
The generated runtime is not a supported downstream dependency. This is not a
signed external-update protocol, updater, cache or rollback implementation.

## Sequence and assertions

| Cold start | Code / database | Expected result |
| --- | --- | --- |
| Seed | v1 / v1 | Two distinct Unicode rows committed in a Room transaction |
| Upgrade | v2 / v1 → v2 | Explicit migration adds `priority NOT NULL DEFAULT 7` and unique title index; Room validates its generated schema |
| Restart | v2 / v2 | Existing rows/defaults and new v2 row with priority 9 survive; migration does not run again |
| Downgrade attempt | v1 / v2 | Missing 2→1 migration is rejected; read-only inspection confirms v2 schema, index and all three rows remain |
| Recovery | v2 / v2 | Generated DAO reads original/new Unicode rows; v2 data remains intact and no migration repeats |

Upgrade also intentionally aborts an insert transaction and attempts a duplicate
unique key. Neither attempted row may remain. Every stage checks the actual code
version, schema version, row count, fresh PID and generated database/DAO defining
classloaders. Run and request tokens reject stale observations. The shell uses
InMemoryDexClassLoader; the normal control uses installed APK classes.

There is no destructive migration fallback and no fabricated reverse migration.
The expected downgrade exception must identify the missing **2→1** path; an
unrelated error is not accepted as success. Reopening v2 after this failure checks
that rejection did not silently erase or downgrade data.

## Verification evidence

API 36.1 x86_64/debug: all ten device stages pass (five per packaging mode).
Both versions pass normal/shell lint, exported schemas match the committed
snapshots, and seven host assertion tests pass. These checks use the real Room
implementation and generated DAOs, without instrumentation or database mocking.
No production runtime/plugin fix was required; the only loader adaptation is
the explicitly isolated fixture bundle selector described above.

## Requirements and limits

A payload activation policy must consider persistent schema compatibility, not
only whether old code can be loaded. Once v2 commits a schema migration, a v1
"last-known-good" payload may no longer be usable. Products need a separately
designed compatible-schema strategy, tested reverse migration or coordinated
data recovery policy. Silently enabling destructive fallback would lose data,
not solve rollback; see [Room's migration guidance](https://developer.android.com/training/data-storage/room/migrating-db-versions).

This tests one additive manual migration and a rejected downgrade, not a working
rollback mechanism. It does not prove interrupted-migration recovery, exactly-once
side effects, WAL/concurrent-process races, auto-migrations, Kotlin/KSP, foreign
keys/FTS/views, encryption, library-version changes, resources changing with code,
remote payload security, hot swapping or release/R8 compatibility. Both bundles
are already inside the APK; this cannot establish external-update availability
or integrity guarantees. Other APIs/ABIs require their own device evidence.
