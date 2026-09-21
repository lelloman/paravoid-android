# Downloaded DEX / cold-start selection experiment

This extends the [signed archive gate](ARCHIVE.md) with **real downloaded code**.
It remains fixture-only: it does not replace the entire payload Application or
Activity, implement a production updater, or freeze the VPK protocol.

```sh
ANDROID_HOME=/path/to/sdk python3 compatibility/provisioning/check_signed.py --serial emulator-5584 --cold-dex
```

The existing emulator-only restrictions, four-package scope and SDK prerequisites
apply; `javac` must also be on PATH. The runner compiles the committed `dex/a`,
`dex/b` and `dex/failing` Java sources separately with javac/D8. A and B implement
the same class with different return values. The third implementation throws on
startup. APK/embedded-module inspection asserts that this class is **absent** from
every installed variant. The APK hash must remain unchanged throughout updates.

## Selection and execution

Trusted APK policy explicitly enables `coldDex`; existing unsigned/signed/archive
modes do not execute downloaded code. Only a separately release-signed archive
containing exactly `code/classes.dex` plus its authenticated manifest/inventory is
eligible. Discovery signature, scope, freshness, monotonic counters, archive hash
and the full inner inventory are checked before staging.

One private atomic `pending.bin` record contains both the exact signed head and
archive. Downloading/staging never creates a loader or calls candidate code. A
fixture-only, non-exported provider selects it on the next **process startup**,
before the probe Activity. This provider runs inside the real normal/Paravoid app;
the shell still initializes its original embedded application normally. It is not
an empty-shell or pre-Application bootstrap test.

Before executing anything, startup re-verifies the persisted head signature and
scope, archive hash, manifest signature and every component hash. It constructs a
bounded in-memory module from those verified DEX bytes and uses Paravoid's existing
`ModuleBundle` parser/class-loader path. That path uses Android's
[InMemoryDexClassLoader](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader).
The entry class must belong to that child loader, not a bundled parent definition.
The same verified in-memory byte snapshot is loaded; no extracted DEX file is
reopened between verification and loading.

The candidate becomes `active.bin` only after its test entry method returns
successfully. Selection files use `AtomicFile`; metadata and code are not separate
files that could be mixed between releases. Active version/hash checks prevent
ordinary downgrade or same-version replacement. In-process state is fixed: another
Activity can download B while still reporting A from the same process/loader.
Reports re-invoke the retained entry method before and after the download;
invocation counters ensure warm checks are not just reading a cached label.

With no pending candidate, active code is re-verified and loaded locally, without
a network dependency. Previously accepted metadata expiry or grant revocation
does not disable offline execution. Online discovery continues to enforce expiry
and replay checks. Fresh installation/data-loss/backup rollback and hostile-clock
protection remain outside this experiment.

## Failure and evidence boundaries

A verification failure never runs the candidate. A trusted candidate that throws
enters explicit recovery: the previous active file remains byte-for-byte intact,
but **old code is not automatically run**. Candidate code could have changed
persistent data before failing. The broken pending candidate continues to produce
recovery until a newer, verified forward release replaces it. The pure test entry
has no database effects; no database rollback policy is proven here.

The runner checks staged-only A, same-process non-activation, A→B after restart,
offline startup, corrupt downloads, inner DEX tampering, old-head replay,
signed-but-broken startup, persistent recovery, forward recovery, and corrupted
pending/active files. Local disk faults are deliberately injected only via `run-as`
into fixture-private files; restoring saved active bytes is a **test control**, not
a production recovery API. Process tokens prove cold versus warm execution and
installation markers check app-data retention. Keyed variants also verify that
revocation denies discovery while B remains locally executable.

Corrupt active state currently fails closed and also blocks staging, which checks
the active identity before replacement. Production needs an explicit authenticated
repair path that preserves replay protection; this fixture does not supply one.

This is one process, one tiny DEX and a static entry method. It does not cover full
Application/Activity lifecycle replacement, downloaded resources/assets/native
libraries, worker processes, extraction, crash/power-loss injection, low storage,
real startup-health policy, root rotation or physical ARM64/release/R8 behavior.
Two atomic selection-file operations are not a proven cross-process transaction.
Signed code executes with app privileges; the loader is not a security sandbox.

Reports are written to ignored `build/cold-dex-api{SDK}.json`. Other experiment
profiles retain their previous non-executing behavior.
