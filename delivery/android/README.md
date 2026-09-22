# Shell-owned controls integration

`ShellUpdatesActivity` is a programmatic Android screen, with no payload/resource
dependency. A must include these sources plus `delivery/src` and `paravoid-contract`
in shell build wiring. C owns routing/manifest integration and must establish the
shell-only recovery process, without user Application/providers or generation lease.
No runtime factory/manifest files in other tracks are modified here.

Required initialization in that process:

1. Construct real A verification and C lifecycle implementations from APK policy.
2. Create `DeliveryClient` with its own directory under `Context.getNoBackupFilesDir()`.
3. Call `installedApk(new File(getApplicationInfo().sourceDir))` on every process
   startup. After controller initialization, route installed-APK refresh through
   `controller.refreshInstalledApk` to cancel old retries and reset scheduling
   suppression as well. Missing/bad grants deny updates. Public mode
   never reads or sends a bearer key. Do not reuse an old process's grant after replacement.
4. Create `DeliveryController` with a **single-thread** scheduled executor, clock
   backed by Unix time and `SystemClock.elapsedRealtime()`, main-thread callbacks,
   and metered-network detection. Put its preference file under no-backup storage.
5. Install it using `ShellUpdatesActivity.installController`. For empty bootstrap,
   call `foreground(true)` asynchronously; normal usable-app foreground calls
   `foreground(false)`. No network blocks early component construction.

The controller implements bounded scheduled retries, explicit access retry, cancel,
automatic preferences, unmetered restriction, six-hour foreground checks and C-owned
retention/confirmed quarantine retry. Snapshot availability comes from C, never from
the latest HTTP result. UI intentionally offers no rollback, data deletion, bypass,
credential display, forced process kill or arbitrary intent replay.

Source compilation against Android 36 can be checked with `delivery/check-android.sh`.
This is **not** Android manifest/device/lifecycle acceptance. Recovery-process wiring,
startup checks, replacement notification, APK signature-preserving personalization,
full schema verification and emulator gates remain separate integration work.
