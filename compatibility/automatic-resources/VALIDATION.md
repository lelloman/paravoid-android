# Generated resource pair validation — 2026-09-21

Both isolated x86_64 emulators passed all 19 stages, twice. The final run records
`ro.build.version.sdk_full` to distinguish 36.1 from 36.0; API 30 has no such value.

| Device | System build | Result |
| --- | --- | --- |
| API 30 / Android 11 | `Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys` | 19/19 |
| API 36.1 / Android 16 | `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:user/release-keys` | 19/19 |

Each run verified:

- Normal A: cold startup, explicit recreation, night, day (4 stages).
- Shell without a resource payload: pinned label/theme work; movable values and
  the asset are absent, including during Application/provider and worker startup.
- Shell A, B, A: each has cold startup, recreation, night and day (12 stages).
  App/library generated IDs, styleable lookup, compiled XML, theme resolution,
  Italian/night configuration contexts and asset bytes match the selected version.
  B removes A's `removed` entry and adds `a_added`; restoring A removes `a_added`.
- Corrupt and writable archives fail before a fresh successful Activity report
  (2 rejection stages).

APK path/hash and persistent data marker stay fixed throughout each mode's
sequence. Each cold run has a fresh process token; recreation/configuration stages
retain the same main token/PID and worker PID. Providers report resource access
before Application.onCreate; shell payload Application constructors already see
the selected resources in both processes.

Build-time checks passed for A/B baseline compatibility, identical pinned shell
archives, unchanged resource hashes after trusted-hash code generation, final
APK signing/alignment and installed-content inspection. The generated shell has
exactly `color/shell_accent`, `string/shell_label`, `style/ShellTheme`, with no
movable `res/` files or `assets/content.txt`. Each payload has ten resources, a
compiled layout and the selected content asset.

Resource archive SHA-256 values for these runs:

```text
A 85034bd831fa1a53740e2e288fee79f9cc3c03caf2b906ca92c997944dd755d9
B c7cff3c2bb9db1f75241ebf9bf617fb894b237c15e30d9905e2b38f76a7a293b
```

Additional verification: normal/shell A lint passed; 2 new host tests (including
25 invalid-evidence mutations) and 11 shared resource-split host tests passed.
The unchanged plugin suite's `test` task succeeded up-to-date, reusing its prior
61 passing tests; it was not a fresh execution of those tests.

No production defect was uncovered by these device runs. Setup fixes were confined
to the new fixture: Gradle script wiring, non-translatable probe strings, APK path
resolution via AGP metadata, and recording the correct full SDK property.

This evidence is for plugin-generated resources loaded through **fixture-only**
assembly and startup hooks. Installed code stays at A. It does not validate the
production full-VPK assembler, updater activation, native/Java-resource relocation,
Compose/system-resource cases, ARM64, shrinking or release packaging. See the
[fixture requirements and limits](README.md#test-only-scaffolding-and-remaining-limits).
