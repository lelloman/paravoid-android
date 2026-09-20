# Binder / AIDL probe

Uses the OS fixture's pinned AGP 8.13.2 Java AIDL generator, compile/target 36,
min SDK 28. `contract/` is compiled independently into the peer APK and target
application; the target's generated Stub/Proxy and wire Parcelable classes belong
to the payload, not the installed shell class loader.

```sh
bash compatibility/os/check.sh
ANDROID_SERIAL=emulator-5584 python3 compatibility/os/binder-device-check.py
```

Use a dedicated unlocked emulator (English button labels), Python 3 and adb. The
driver installs/clears only the OS fixture packages. The full OS `--device` suite
also includes this driver. No target instrumentation is installed.

## Scenarios

API 36.1 x86_64/debug: cold binding and explicit unbind/rebind pass in both
packaging modes. No production adapter changes were needed for these scenarios.

- A foreground peer explicitly binds from a separate UID to a completely cold
  target. The target Service runs in its default app process, with no Activity
  entry; its PID must match the initialized Application's PID.
- Typed custom Parcelable request/response, Unicode, nullable typed lists and
  null arguments cross the real Binder proxy, not an in-process shortcut.
- A one-way AIDL callback returns data to the peer, including Service instance
  identity and PID. Synchronous calls run off the peer main thread; the target
  verifies execution on a Binder thread and checks `Binder.getCallingUid()`.
- An intentional `IllegalArgumentException` crosses the synchronous remote call.
- Final unbind destroys the Service. Explicit rebinding creates a new Service
  instance without restarting the peer; normal unbinding does not produce
  `onServiceDisconnected`.
- Each connection verifies payload defining-loader identity and absence of the
  generated interface from the shell's parent loader.

## Integration requirements

Enable `buildFeatures.aidl`. Both peers need compatible AIDL and Parcelable wire
definitions; the target's copy being in a payload does not remove this contract.
Keep the Service's class name and declaration stable in the installed manifest.
This fixture exports its Service behind a flavor-specific **signature permission**;
the peer requests both fixture permissions and shares the debug signing key. Do
not copy it as an unprotected production endpoint. Permission rejection by an
untrusted third APK is not yet tested.

Use explicit service binding and normal Android threading/lifecycle rules. See
[Android AIDL documentation](https://developer.android.com/develop/background-work/services/aidl)
and [bound Service lifecycle](https://developer.android.com/develop/background-work/services/bound-services).

## Limits

This is typed AIDL Parcel serialization, not arbitrary `Bundle` object discovery.
Custom Parcelables in binding Intents/Bundle values, target `android:process`,
isolated services, foreground services, client death, large transactions,
file descriptors, concurrent load/backpressure, version-skewed contracts, release
shrinking and other Android versions remain unvalidated. No remote payload update
or ABI/schema migration is exercised.
