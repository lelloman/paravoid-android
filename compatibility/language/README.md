# Language, generated code and discovery compatibility

This ordinary downstream fixture applies the Kotlin Android, Serialization and
Parcelize plugins alongside Paravoid. It pins Kotlin 2.2.21 (including
`kotlin-reflect`), kotlinx.serialization 1.9.0 and kotlinx.coroutines 1.10.2.
It uses the repository's AGP 8.13.2 / Gradle 8.13 toolchain, without Hilt or Compose.

## Run

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/language/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/language/check.sh --device
```

The device runner requires Python 3.8+, adb on PATH and a dedicated unlocked
emulator. It installs both production APKs and clears only these fixture apps'
data. It saves state, backgrounds the app, kills its exact process, and relaunches
the launcher to verify restoration in a new process. Preferences are observation
output, not a substitute for saved-state restoration; unique run tokens reject
stale results.

## Verified on API 36.1 / debug

Sixteen checks run in normal and shell packaging, both cold and after actual
process death: **64 passing assertions**.

- Generated JSON serializers: sealed polymorphism, nested generic lists and defaults.
- Kotlin reflection: constructor defaults, properties, annotations and generic types.
- Java reflection: implicit `Class.forName`, construction and method invocation.
- Dynamic proxies with an explicit interface loader and the thread context loader.
- Generated Parcelable round-trip and Java serialization round-trip.
- ServiceLoader with an explicit loader, inherited thread loader, new executor,
  and coroutine dispatcher.
- Default ServiceLoader during the Application constructor, provider startup and
  Application `onCreate`, including provider-before-`onCreate` ordering.
- Generated Parcelable and Serializable objects restored from Activity saved state.
- Payload defining-loader identity and absence of application classes in the shell
  parent loader.

## Failure found and fixed

The initial normal control passed. Shell default discovery failed on the
Application initialization thread, a newly created thread and a new executor;
context-loader proxy creation also failed. These were four mechanisms / eight
failed assertions across cold and restored runs. Coroutine discovery already
passed that baseline.

The initialization thread retained the installed shell's context classloader.
The shell now sets that thread's context loader to the payload loader immediately
after creating it, before user constructors and provider initialization. New
threads inherit it. The constructor/provider checks additionally guard this
timing. Libraries remain in the payload, not the shell's ordinary DEX.

## Boundaries

This does not certify every reflection operation or library version. Existing
threads, shared pools, custom classloaders, R8 and cross-payload-version saved
state need separate probes. Java service descriptors still live in installed APK
resources; this is not independent resource delivery. SDK D8 Kotlin metadata
warnings remain visible even though these builds and runtime checks pass.

Plugin/API references: [Serialization](https://kotlinlang.org/docs/serialization.html),
[reflection](https://kotlinlang.org/docs/reflection.html),
[Parcelize](https://developer.android.com/kotlin/parcelize).
