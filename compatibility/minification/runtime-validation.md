# Payload R8: dependency rules and runtime validation

The experimental `minifyPayload` path collects dependency consumer ProGuard
rules from the variant's transitive AARs and JARs, including project libraries and
`META-INF/proguard`. It selects version-targeted `META-INF/com.android.tools/r8*`
rules for the SDK R8 that actually packages the payload, using AGP 8.13.2's matching
semantics. It records the selected, deduplicated rules in
`build/outputs/paravoid/<variant>/payload-consumer-rules.pro` with content hashes.

The plugin also generates rules for manifest components, Paravoid entry points
and metadata names/values that resolve to payload classes (including Startup
initializers). It applies the app's `payloadProguardFiles` after dependency rules.
Libraries can still ship incomplete rules; app-specific reflection, JNI and
serialization rules remain the app's responsibility. A successful
build, contract check, or Store validation does not establish runtime safety.

## Historical failures observed in Accordomi

These failures predate automatic consumer-rule collection and were observed with payload R8 8.6.2-dev, Kotlin 2.2 metadata,
AndroidX Startup, Hilt, Navigation Compose, and DataStore Preferences:

| Runtime symptom | Missing protection |
| --- | --- |
| `ClassNotFoundException: androidx.emoji2.text.EmojiCompatInitializer` | Startup initializer classes named in manifest metadata |
| Hilt ViewModel falls back to the default factory, then reports `NoSuchMethodException` | ViewModel runtime names and entry points |
| `No @Navigator.Name annotation found` | Navigator classes and runtime annotations |
| Cannot construct `BackStackEntryIdViewModel` | Constructors invoked by the default ViewModel factory |
| `Field preferences_ ... not found` in `MessageSchema` | Generated protobuf field names used to read saved DataStore preferences |

The DataStore failure was missed by a fresh-install startup smoke test. Reading
existing preferences exercises a different path from an empty preferences store.
Preserve user data when repairing this failure; deleting preferences hides the
problem and loses settings.

## App-supplied rules

Accordomi uses the following conservative rules with its current dependencies.
They are examples, not a complete rule set for every app or library version.
Add rules for app-specific reflection, JNI, serialization, and service discovery.

```proguard
-keep class * implements androidx.startup.Initializer { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepattributes RuntimeVisibleAnnotations
-keep class * extends androidx.navigation.Navigator { *; }
-keep class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }
```

Wire these through `paravoid.payloadProguardFiles.from("proguard-rules.pro")`.
The DataStore namespace is its shaded protobuf runtime; a rule for
`com.google.protobuf.GeneratedMessageLite` alone does not cover it.

## Release validation

Before publishing a minified VPK:

1. Archive the exact VPK, mapping file, and source commit together.
2. Exercise the minified artifact, not a normal APK or an older embedded payload.
   Verify the active payload version in the update UI.
3. Save non-default settings, stop the app, and cold-start it. Verify that the
   settings survive and that reads and writes both work.
4. Also test upgrading an existing installation containing saved preferences.
   Preserve the installation's data throughout the update.
5. Exercise each main destination and the app's reflection-dependent features.
6. Inspect crash logs. Decode obfuscated names with that artifact's mapping.

R8's warning that Kotlin metadata 2.2 exceeds its supported 2.1 version is a
separate toolchain limitation. A passing smoke test does not eliminate it, and
it must not be used to explain a specific runtime crash without its stack trace.

If an already published payload is broken, validate a higher-version repair
payload against the installed shell contract and publish that repair. Do not
assume a shell replacement or deletion of user data is required.

## Automated retained-data regression

The [minification fixture](README.md) has an Android library with an otherwise
unused serialized settings class kept solely by its consumer rules, plus a
reflection-only JAR dependency kept by `META-INF/proguard`. Its upgrade runner
installs an unminified seed, saves a non-default choice, replaces the APK with the
minified version using `adb install -r`, and cold-starts it again. It checks both
runtime lookup and byte-for-byte preservation of the existing settings file in
normal and shell modes. No data clear occurs between seed and replacement.

This is a serialization/schema regression, not a substitute for DataStore, Room,
Navigation or authenticated workflows in the actual downstream app. Keep the
application-specific saved-data checks above when adopting payload shrinking.
