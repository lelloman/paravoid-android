# Networking, JSON adapters and KSP compatibility

Pinned fixture: Kotlin 2.2.21, KSP **2.2.21-2.0.4 / KSP2**, Retrofit and converters
2.11.0, OkHttp 4.12.0, Gson 2.11.0, Moshi/Moshi codegen 1.15.2, kotlin-reflect
2.2.21 and coroutines 1.10.2. The repository pins Gradle 8.13 / AGP 8.13.2.
The app declares minSdk 28; device evidence is API 28 and API 36.1/debug/x86_64, not every
supported Android version.

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/network/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/network/check.sh --device
```

Device checks require Python 3.8+, OpenSSL with `req -addext` support, adb on PATH
and a dedicated unlocked emulator. The driver starts loopback-only HTTP/HTTPS
servers, creates its own ephemeral adb reverse mappings and removes them on exit.
It installs and clears only the fixture apps. No external HTTP service is needed.
INTERNET permission and a
network security configuration belong in the installed manifest/resources; the
fixture permits cleartext only for 127.0.0.1, not globally.

The driver uses distinct explicit device ports selected from its reserved host
server ports, with `--no-rebind`: an existing mapping is never overwritten. A
first HTTPS attempt exposed an ADB 34.0.4 host-server abort with multiple `tcp:0`
reverse mappings. Explicit ports avoid that harness failure. An emulator host
crash also invalidated an earlier attempt; neither is counted as a Paravoid failure.

## Evidence

76 device assertions pass: 19 checks × normal/shell × cold/restored process.
The same production APK driver also passes all 76 assertions and eight server
audits on API 28, including TLS trust/hostname rejection and real process-death
restoration. No API 28-specific network fixture or runtime changes were needed.
The complete corrected harness passed twice consecutively with fresh run tokens
and certificates. All 50 existing host tests and both-mode lint also passed;
no production runtime/plugin change was required for these cases.
Eight independent HTTP/HTTPS server audits verify exact request counts, interceptor headers,
unique run/PID tags, and POST JSON bodies. This prevents a stale response or a
skipped request from masquerading as success. The driver verifies actual process
death, a new PID and saved-state restoration, then repeats fresh requests; it does
not claim in-flight HTTP calls survive process death.

Covered: Retrofit service proxies and Java default methods; Gson generic/nested
models, field-name annotations and Unicode round trips; Moshi generic KSP-generated
adapters and reflection-based Kotlin adapters with constructor defaults; custom
qualifiers through reflection; main-thread success/cancellation callbacks; suspend
APIs and cancellation propagation to OkHttp; HTTP 422 responses and HttpException;
malformed JSON; timeouts; disk cache hits; network security policy and payload
classloader isolation. Generated-adapter cases do not install a reflection fallback.

HTTPS checks exercise a client explicitly trusting an ephemeral test certificate,
the default client rejecting that untrusted certificate, and hostname verification
rejecting a mismatched name even with the certificate trusted. The server requires
TLS 1.2 or newer. No permissive TrustManager/HostnameVerifier is used and the
platform trust store is not modified. The private key stays on the host in a
temporary directory removed at exit; only the public certificate reaches the app.
This test-only trust setup is not required by Paravoid or a production trust policy.

## Known toolchain limitation

With these pins, KSP generation for a model using a custom JsonQualifier fails
in **both normal and shell** builds, before Paravoid payload packaging. This
matches [Moshi issue #1874](https://github.com/square/moshi/issues/1874). Ordinary
models use KSP; the qualifier model deliberately uses KotlinJsonAdapterFactory.
This is not a Paravoid runtime fix, nor evidence that generated qualifiers work.

Retained negative reproducer (expected failure in both processing tasks):

```sh
./gradlew -p compatibility/network -PprobeGeneratedQualifier=true \
  kspNormalDebugKotlin kspParavoidAndroidDebugKotlin --continue
```

Omit that property to return to the passing fixture. Other Moshi/KSP combinations
need their own verification. The existing SDK D8 Kotlin-metadata warnings remain
visible; passing these cases does not certify every metadata consumer or R8.

Still untested: public DNS/proxies, HTTP/2, WebSockets, certificate pinning/mTLS,
authentication refresh, multipart/streaming transfers, offline transitions and
other library versions. R8/shrinking remains rejected by the core plugin. The
fixture deliberately uses older pinned stacks; it does not claim latest-version
support or require every downstream app to adopt these versions.
