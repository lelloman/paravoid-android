# Controls launcher fallback

The complete profile defaults `paravoid.controlsLauncher` to false. Apps can
render their own update UI from `ParavoidUpdates.get()`; see README.md and V1.md
section 9. Setting `paravoid { controlsLauncher = true }` emits a public
MAIN/LAUNCHER alias named
`com.lelloman.paravoidandroid.runtime.UpdatesLauncher`, targeting the existing
private `ShellUpdatesActivity` in `:paravoid_recovery`. No extra payload Activity,
resource ID, custom launcher integration, or dynamic shortcut is necessary.
The alias uses the application icon and the label `App updates`.

The complete-v1 fixture explicitly enables the alias for this launcher test.
Adding/removing the alias changes the installed shell contract and requires
rebuilding the shell.
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

## Reopened-screen observer regression

The Pezzottify experiment exposed an Android lifecycle ordering bug: a new
controls Activity subscribed before the old Activity stopped, and the old
`listen(null)` call detached the new observer. Delivery continued but visible
status froze. `72f5816` gives each Activity a stable listener and removes it only
if it still owns the controller subscription; queued callbacks check ownership.

The installed script now opens another controls instance and requires Cancel
download to visibly render `Update: CANCELLED`. On API 36.1 this fails on the
pre-fix shell (`/tmp/paravoid-observer-baseline36.log`) and passes on the fixed
shell (`/tmp/paravoid-observer-fixed36.log`). Full delivery host tests, including
old-stop-after-new-start, current unsubscribe and old-screen resubscribe, plus
Android source compilation passed (`/tmp/paravoid-controls-observer-host.log`).
The fixed observer also passes the installed script on API 30
(`/tmp/paravoid-observer-fixed30.log`).
