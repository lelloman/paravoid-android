# Component startup failure acceptance

The complete runtime observes uncaught exceptions on the Android main thread after
a generation lease is acquired. It recognizes only framework component construction,
provider attach, Activity create/start/resume and service creation stack boundaries.
It reports startup failure and always delegates the original throwable to the
previous crash handler; reporting failure cannot swallow the original crash.
It does not install a process-wide default handler or classify arbitrary callbacks.

Main-process observation stops after its first successful rendered frame. Before
Application creation it covers providers/construction; after Application creation
a background process is eligible only while the generation remains in trial.
A handler installed by user Application.onCreate is wrapped after that callback,
preserving delegation. A later replacement of the main thread's handler can bypass
observation and is not supported as a guaranteed quarantine path. Background-thread
exceptions, native aborts, SIGKILL and arbitrary later callbacks are not caught by
this observer. Existing incomplete-trial handling remains separate.

Failures before policy/lifecycle initialization cannot safely manufacture a
generation quarantine or reset trust; shell repair may be required. Custom
AppComponentFactory implementations remain rejected by the manifest plugin;
this change does not broaden the supported factory contract.

## Reproduction

With the two owned disposable AVDs already booted and adb on PATH:

```sh
ANDROID_HOME=/path/to/sdk PARAVOID_GRADLE=/path/to/gradle \
PARAVOID_GRADLE_USER_HOME=/path/to/cache \
bash integration-v1/startup-matrix.sh emulator-5586 Restart30 emulator-5584 Restart36
```

The driver builds the normal control and signed embedded shell for each
`-PstartupFault` and runs `startup-failures.py`. Faults exist only in fixture
payload code, never in production verifier/runtime switches. The cases are
provider/Activity/service constructors and their onCreate callbacks, plus a
negative case: Activity creation failure after a healthy first frame must leave
selection unchanged and allow a healthy relaunch. Normal controls exhibit the
injected crashes; shells quarantine startup failures and expose payload-free
recovery. The driver restores ordinary empty/public A outputs on success.

Passed 2026-09-23 on API 30 and 36.1 x86_64: all seven cases per API on the final
observer code. Logs: `/tmp/paravoid-startup-matrix-final.log` and
`compatibility/complete-v1/build/startup-cases/`. Runtime unit suite also passed
(`/tmp/paravoid-startup-units-final.log`), including boundary classification,
cause wrapping, different-thread/later-crash rejection, exact throwable delegation
and reporting failure. No physical ARM64 or arbitrary custom crash-handler claim.

## Corruption while a recovery confirmation is open

Build `generation=broken`, `payloadVersion=2`, `bootstrap=embedded` and
`startupFault=none`, then run `integration-v1/quarantine-controls.py` with its
usual `--serial`, `--avd`, `--apk` and `--corrupt-during-confirmation`.
After actual Application failure and a cancelled confirmation control, the test
opens a new retry dialog, corrupts the selected generation's DEX and confirms.
The real lifecycle returns INTEGRITY; selection/quarantine and security bytes
remain unchanged. Recovery PID survives with no payload mappings or leases.

Passed on both API 30/36.1, 2026-09-23, runtime `0e79cb5`. Logs:
`/tmp/paravoid-recovery-corrupt{30,36}.log`; build:
`/tmp/paravoid-recovery-corrupt-build.log`. This is installed corruption-race
evidence, not stale-selection/pending-repair/live-lease device evidence.
