# Direct Boot: payload execution before PIN unlock

This standalone fixture uses one ordinary Activity, a custom
`ParavoidAndroidApplication` subclass and one non-exported, `directBootAware`
receiver. It has no AndroidX initializer or DI dependency. Both packaging modes
run identical source code; this tests bundled payload loading, not remote updates.

## Run

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew -p compatibility/direct-boot \
  assembleNormalDebug assembleParavoidAndroidDebug lintNormalDebug lintParavoidAndroidDebug
ANDROID_SERIAL=emulator-5584 python3 compatibility/direct-boot/device-check.py --allow-pin-and-reboot
python3 -m unittest discover -s compatibility/direct-boot -p test_device_check.py
```

Use a **fresh, disposable** API 36.1 x86_64 emulator with file-based encryption,
AVD name `paravoid_direct_boot`, display 720x1280 and no existing PIN/password.
Start with user 0 unlocked and adb available on PATH. The host driver refuses a
physical device, another AVD name, an existing credential or non-FBE storage.

The explicit option authorizes setting the public fixture PIN **246810** and a
real framework-managed reboot. The driver enters that PIN through the lock-screen
UI only after both locked-stage probes finish. Finally it removes its PIN,
restores the previous lock-screen-disabled setting, force-stops only the fixture
packages and resets their exact-alarm app-op to default. APKs remain installed.
Do not run alongside other device tests or against personal emulator data. If the
host is interrupted, this disposable AVD may retain PIN 246810; recover it via
the lock screen and `adb -s SERIAL shell locksettings clear --old 246810` on that
AVD only. No account credentials or user PINs are requested.

## Assertions

1. While unlocked, write different Unicode values into device-protected (DE) and
   credential-protected (CE) preferences. Persist a fresh run token in DE storage.
2. Reboot with the PIN set. Verify a new kernel boot ID and Android user state
   `RUNNING_LOCKED`, not merely a visible keyguard after an ordinary screen lock.
3. Real `LOCKED_BOOT_COMPLETED` must cold-start the Application and receiver from
   the payload. Verify increased boot count, no Activity and the expected class
   loader. Read the DE value; accessing the CE preferences must throw.
4. Schedule a real exact alarm from the locked receiver. Its explicit immutable
   PendingIntent targets that same Direct-Boot-aware receiver and carries a
   payload-only Parcelable. Require correct decoding while the user is still
   locked; no deferred initialization or BOOT_COMPLETED may have occurred yet.
5. Enter the PIN. The runtime-registered USER_UNLOCKED callback and manifest
   BOOT_COMPLETED callback both attempt deferred initialization. CE data must now
   be readable, with one recorded initialization despite two attempts. Require
   no Activity and no second Application onCreate in this process-lifetime case.
6. After unlock, independently read persisted DE observations via `run-as` and
   verify delivery/initialization counts. Before unlock, the host observes JSON
   logcat events because credential-backed `run-as` working directories need not
   be accessible. Log tokens, boot identity and count checks reject stale results.

## Integration requirements and limits

The installed manifest must declare RECEIVE_BOOT_COMPLETED and mark the receiver
`directBootAware`. The Application is started with that receiver; its startup
logic must therefore be safe while CE storage is locked. This fixture explicitly
uses `createDeviceProtectedStorageContext()` and defers credential-dependent work
until unlock. It does **not** globally switch default storage to DE or move secrets
there. Ordinary Android apps have the same obligation; see Android's
[Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot).

The deferred initializer is fixture-owned, single-process and deduplicated per
boot. Its preference updates are not an exactly-once transaction across crashes.
Application onCreate is not an unlock notification; downstream code must handle
unlock explicitly if a process was already running. Only information genuinely
needed before unlock should be stored in DE storage.

This does not establish Direct Boot compatibility for Hilt, WorkManager, Room,
AndroidX Startup providers, native libraries or arbitrary third-party SDK startup.
It does not cover killing the process between locked delivery and unlock,
secondary users/work profiles, encrypted databases/files, PIN changes, natural
Doze, remote payload caching, power loss, release/R8, other APIs or other ABIs.
