# Controls launcher fallback

The complete profile defaults `paravoid.controlsLauncher` to true. It emits a
public MAIN/LAUNCHER alias named
`com.lelloman.paravoidandroid.runtime.UpdatesLauncher`, targeting the existing
private `ShellUpdatesActivity` in `:paravoid_recovery`. No extra payload Activity,
resource ID, custom launcher integration, or dynamic shortcut is necessary.
The alias uses the application icon and the label `App updates`.

Opting out is an explicit integrator choice; see V1.md section 9. Adding/removing
the alias changes the installed shell contract and requires rebuilding the shell.
External apps may open the exported UI, but it does not interpret incoming
actions/extras as commands; restart and quarantine retry remain confirmed UI
operations. Opening controls may run the existing automatic discovery policy,
as with any recovery entry, but never starts payload code itself.

## Reproduction on an owned disposable emulator

```sh
python3 compatibility/complete-v1/prepare.py
ANDROID_HOME=/path/to/sdk ./gradlew -p compatibility/complete-v1 \
  assembleNormalDebug assembleParavoidAndroidDebug
python3 integration-v1/controls-launcher.py --serial emulator-5584 --avd Restart36 \
  --apk compatibility/complete-v1/build/outputs/paravoid/paravoidAndroidDebug/shell.apk
```

The test replaces only the complete-v1 fixture app. It checks launcher resolver
registration; first-ever cold entry before any shortcut registration; visible
controls with no payload process, mapped generation or lease; then a healthy
embedded generation and cold controls entry after clearing all dynamic shortcuts.
Selection remains unchanged and unsolicited restart extras are inert.
No network server is needed. This is a direct launch of the manifest-resolved
launcher component, not an assertion about every OEM launcher's visual layout.

Installed evidence (2026-09-23): API 36.1 Restart36 / emulator-5584 passed against
production `b73264a`; `/tmp/paravoid-controls-device.log`. Normal and complete
embedded fixture builds passed (`/tmp/paravoid-controls-build.log`). The initial
test expected mixed-case button text; Android renders it uppercase, so the UI
assertion now compares case-insensitively. No production change was needed.

API 30 Restart30 / emulator-5586 passed the same installed test using that same
embedded shell artifact; `/tmp/paravoid-controls-api30.log`. The fixture was
force-stopped afterward. Neither run establishes every OEM launcher's appearance.
