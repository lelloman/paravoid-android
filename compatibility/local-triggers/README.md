# Shared local trigger fixture

This fixture exercises two independent auto-downloading complete shells, one check-only
shell, and one shell with an intentionally incorrect distributor certificate pin. Each has
three signed VPK versions. No per-app WebSocket is enabled.

Build the Store debug and instrumentation APKs against the locally staged IPC AAR (see
`lellostore/docs/PARAVOID_DELIVERY.md`), then run on a **dedicated disposable emulator**:

```sh
python3 compatibility/local-triggers/device-test.py --serial emulator-5590
# Reuse the same built artifacts on another disposable API 30/36 emulator:
python3 compatibility/local-triggers/device-test.py --serial emulator-5592 --skip-build
```

The runner rejects physical devices, derives the Store package and signing certificate from
its built APK, and uses only `com.lelloman.paravoidfixture.trigger.*` shell packages. It also
installs the debug Store and its test APK on the selected emulator. It clears only fixture
app data. Payload isolation uses SIGKILL of each fixture main PID, preserving scheduled
work; API 30 userdebug images may require adb root. Foreground activation clears fixture
tasks left by this simulated process death, without force-stopping their jobs. The isolated signed-delivery HTTP server uses port 18765 and an adb reverse. Run
emulator gates sequentially. Build logs, instrumentation output and HTTP request traces are
written to the ignored `build/device` directory; generated signing keys stay under `build`.
`--store-root` and `--sdk` allow alternate checkout/SDK locations.

The Store instrumentation supplies fixture catalog entries and invokes the production
WebSocket listener and polling entry point. Real Binder authentication, Android scheduling,
signed metadata/VPK verification, staging, and foreground activation run without substitutes.
The check-only shell discovers offers without staging, the wrong certificate is rejected,
and background triggers must not launch the payload process. Store APK version filtering is
also covered by its host tests. This gate does not exercise a production account, server-side
publication endpoint, real-app migration, or exact background execution deadlines.

Validated on 2026-09-29 on API 30 and API 36.1 emulators: both the WebSocket event
and polling paths passed staging, check-only behavior, certificate rejection, payload
isolation, and foreground activation checks.
