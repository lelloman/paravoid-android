# Consolidated complete-VPK handoff gate

Build the ordinary production fixture first, then run the cross-track test:

```sh
ANDROID_HOME=/path/to/sdk python3 compatibility/automatic-resources/production-check.py
bash integration-v1/run.sh
```

Do not run the fixture rebuild concurrently with this consumer. The test uses
real AGP/D8-generated A/B payload DEX, compiled Android resources/assets, Java
resources and exported ledgers. It produces new complete, release-signed VPKs and
head envelopes using fresh throwaway RSA-3072 keys; private keys are not retained.
The integration-only policy binds the fixture installed contract, trust and local
endpoint under a new fixture identity. It is not the production APK policy carrier.

No archive verifier, metadata verifier, HTTP transport or lifecycle fake is used:

1. Verify/materialize embedded A using `VpkWriter`/`CompleteVpkVerifier` and C.
2. Acquire A in a fresh JVM and report startup health through the shared lease.
3. B downloads an actual VPK B from its Python reference server, authenticates the
   head and hands off to C. Assert it remains pending, with A still selected.
4. A fresh JVM verifies/selects B and checks all component paths belong to it.
5. Reject signed A discovery after B advanced the lineage/revision history.
6. Stop the server and reopen verified B offline in another JVM.

All six stages pass on the Linux host, 2026-09-22. This is genuine signed-content
and process/storage integration but **not execution of Android DEX on the host**,
an installed shell's Application/provider lifecycle, or Android sandbox/lease
proof. Existing API 36.1 primitive tests and historical embedded loader tests do
not substitute for that end-to-end device gate. No phone is used by this script.

The test creates its own `/tmp/paravoid-full-vpk.*` directory and removes it on
exit, restoring write permission only on its own intentionally immutable fixture
tree for cleanup. Metadata-only shared vectors remain a separate stable input
set; these real VPK fixtures are generated reproducibly from the build inputs,
except for explicitly ephemeral keys/endpoint/head times.

## Remaining consolidated work

- Complete Gradle DSL/tasks and production installed-policy carrier.
- Credential authority tied to the currently installed APK across old processes.
- Early loader, boot-clock adapter, shell-only recovery process and controls wiring.
- First-initialization recovery, storage admission and unavailable-component adapters.
- Installed-app API 30/36.1 fault/repair tests; real-app, signed-release and physical
  ARM64 acceptance; independent format/security review and grant-block ID review.

Track B/C handoff notes remain accurate about their narrower evidence. The imported
branches are preserved; this checkout continues on `v1/packaging` with shared
foundation commits included only once.
