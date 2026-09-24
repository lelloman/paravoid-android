# Optional crash recovery

Complete packaging (Android API 30+) can keep update access available after an
uncaught Java/Kotlin exception. The feature is **off by default**. Enable it in a
new shell APK:

```groovy
paravoid {
    packaging = 'complete'
    crashRecovery {
        enabled = true
        updater = 'default'
    }
    updates {
        enabled = true
        // Existing baseUrl, authentication and trustPolicyFile configuration.
    }
}
```

The default updater uses the shell's existing delivery configuration and installed
APK credential. Recovery automatically **checks** once when opened. The user must
choose **Update** to download a verified offer, then confirm **Restart** to apply
it. If discovery changes between checking and downloading, the new offer requires
another explicit Update action. Automatic download preferences do not apply to
this screen. `controlsLauncher` remains an independent option, off by default.
When crash recovery is enabled, the existing shell controls entry also opens this
screen and uses the selected updater.

## What happens after a crash

1. The shell records bounded crash details in private, non-backed-up storage.
2. If the app is visible, it attempts to open its private recovery Activity in
   `:paravoid_recovery`. Android can deny this launch; a durable marker routes the
   next normal app launch to recovery before loading that payload again.
3. The shell delegates the original exception and thread to the previous handler.
   The failed process terminates. It is never resumed after an uncaught exception.
4. Recovery runs without payload Application initialization, a payload class
   loader or a generation lease. Network and provider work run on a serial worker.
5. Restart stops the other app processes before acknowledging the crash and
   launching the app. A staged repair takes effect on the next cold selection.

Crashes after the first frame, background-thread exceptions, and named-process
exceptions are included. The existing startup-trial quarantine remains active.
**Retry app** explicitly permits another attempt at the same quarantined generation
when no repair is pending; it does not roll back or clear application data. Another
failure creates a new record and returns to recovery.

The screen offers crash details with Copy, Check again, Update, Cancel, Restart or
Retry app, and Close. Leaving it cancels cancellable network work. Activity
recreation attaches to the existing operation. Verification/staging is a short,
non-cancellable boundary. An already staged update remains restartable offline.

Records contain the timestamp, app/shell version, contract and payload identity
when known, process/thread, exception messages and bounded stack traces. At most
four records of 64 KiB each are retained. No preferences, logcat or credential
store are collected, and reports are not uploaded. Exception messages themselves
may contain app data; review details before copying them elsewhere.

This handles uncaught managed exceptions reaching the installed handlers. Native
crashes, ANRs, force-stop, OS kills and failures that prevent writing storage are
outside its guarantee. Apps that replace exception handlers later must delegate
to the handler they replace; handlers are installed around startup, not policed
continuously. A broken shell still requires a repaired APK.

## Custom updater

A custom provider replaces discovery and download transport. Paravoid continues
to own the screen, verification, admission, replay floors, installed-credential
binding, storage reservation and staging. A provider returns a **signed Paravoid
discovery envelope** and writes the offered **signed VPK**. It cannot turn unsigned
metadata or arbitrary bytes into an accepted update.

Create a small Java library module:

```groovy
// recovery-provider/build.gradle
plugins { id 'java-library' }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    compileOnly "com.lelloman.paravoid:paravoid-recovery-api:$paravoidVersion"
}
```

Use the same publishing group/version as your other Paravoid modules (JitPack
coordinates use the repository's JitPack group). Then in the Android app:

```groovy
dependencies {
    paravoidRecoveryImplementation project(':recovery-provider')
}
paravoid {
    packaging = 'complete'
    crashRecovery {
        enabled = true
        updater = 'custom'
        providerClass = 'example.recovery.AppRecoveryProvider'
    }
}
```

Implement `com.lelloman.paravoidandroid.recovery.RecoveryUpdateProvider` with a
public, no-argument constructor:

```java
public final class AppRecoveryProvider implements RecoveryUpdateProvider {
    public AppRecoveryProvider() {}

    @Override public byte[] check(RecoveryRequest request, Cancellation cancel)
            throws Exception {
        // Fetch a signed discovery envelope for request's exact installed scope.
        // Return signed no-compatible-release/shell-update-required envelopes too.
        return transport.fetchSignedHead(request, cancel);
    }

    @Override public void download(RecoveryRequest request, RecoveryUpdate update,
            OutputStream destination, Cancellation cancel) throws Exception {
        transport.copyVpk(request, update.releaseId, destination, cancel);
    }
}
```

`transport` above represents your own shell-safe implementation, not a Paravoid
API. A runnable HTTP example is in
[`FixtureRecoveryProvider`](../compatibility/complete-v1/recovery-provider/src/main/java/com/lelloman/paravoidcompat/recovery/FixtureRecoveryProvider.java).

Provider requirements:

- Code-only JARs and code-only transitive dependencies. No AAR resources, JNI,
  service-loader files, Android manifest components, payload dependencies, app DI
  containers, app Application or app-owned UI. Static references to unavailable
  classes and invalid provider constructors are rejected during packaging.
  Reflective access cannot be checked reliably: do not use reflection to load
  payload classes. Java compiler lambdas/string concatenation are desugared by D8.
- No required Kotlin, Compose or coroutine dependency. Methods are blocking worker
  calls. Keep all work within the call; do not start detached background work.
- Set bounded connection/read timeouts. Check `Cancellation.check()` while copying
  and register a fast disconnect action with `onCancel`; close that registration
  when finished. Do not retain the destination or cancellation object after return.
- Bound discovery response size before allocating it. Honor `archiveSize` and write
  exactly the requested archive. Do not close the shell-owned output stream.
- `RecoveryRequest` supplies the installed scope, configured base URL, a private
  provider directory and an optional Authorization header. Do not log or persist
  credentials, forward them across origins, or follow unvalidated redirects.
  Custom transport is trusted downstream shell code, not a security sandbox.
- Throw `RecoveryUpdateException(401)` or `(403)` for authentication rejection so
  the shared suppression state is retained until an explicit retry. Other
  exceptions display a generic retryable error without exposing credentials.
- Custom downloads restart from byte zero on retry; the default HTTP transport
  retains its existing validated resume behavior.

A durable marker surrounds provider initialization and calls. If the recovery
process dies during provider execution, reopening recovery does not automatically
invoke it again. Check again explicitly retries it. The recovery process itself
never installs the crash-launch handler, preventing recursive recovery launches.

Enabling/disabling recovery, changing the provider or any of its dependencies,
and changing installed update policy require a **new shell APK**. Subsequent
compatible payload repairs can be distributed as VPKs. A currently installed
shell without this feature cannot acquire it through a payload update.

## Validation

```sh
./gradlew :paravoid-runtime:testDebugUnitTest :paravoid-gradle-plugin:test
bash delivery/test.sh
bash lifecycle-tests/run.sh
python3 compatibility/complete-v1/recovery-check.py \
  --serial emulator-5584 --avd Medium_Phone_API_36.1
```

The device script accepts only the named disposable emulator. It covers both
providers, startup/repeated/main/worker/secondary-process failures, shell-only
recovery, a provider process death, explicit download/restart, and a genuine signed
VPK repair with a fixed installed shell and preserved private app data. It does
not reproduce every downstream serializer or R8 failure; keep saved-data upgrade
tests in downstream apps too.

Validated on 2026-09-24 on disposable x86_64 emulators:

| Android | Payload | Providers | Result |
| --- | --- | --- | --- |
| API 36.1 | Unminified | Default and custom | Crash/retry/repair, provider death guard, screen recreation passed |
| API 30 | R8 minified | Default and custom | Crash/retry/repair and provider death guard passed |
| API 36.1 | Feature disabled | Original updater | Original quarantine and controls preserved; no crash records created |

Local Maven publication and the standalone consumer build also passed with the
new API module. The existing real signed-VPK integration flow passed embedded
bootstrap, HTTP staging, cold activation, replay rejection and offline execution.
