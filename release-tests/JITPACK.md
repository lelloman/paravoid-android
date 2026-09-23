# GitHub/JitPack public distribution; Fucina internal development

Public release source: `github.com/lelloman/paravoid-android`. JitPack builds a
selected tag/commit and serves the Maven artifacts. Fucina remains internal;
this setup does not change mirror direction, enable the disabled Fucina push
remote, or assume a Fucina package-registry endpoint.

`jitpack.yml` selects JDK 17, installs the required SDK packages and explicitly
publishes all six projects (including the three included plugin builds) to Maven
local for JitPack collection. The runner needs Android command-line tools and
accepted SDK licenses. No emulator, Python fixture keys, Maven Central account,
PGP key or Gradle Plugin Portal upload is required for this publication path.

The scripts take JitPack's `GROUP`, `ARTIFACT` and `VERSION`, aligning internal
dependencies with `com.github.lelloman.paravoid-android:<module>:<tag-or-commit>`.
Without the explicit group property, internal/local publications retain
`com.lelloman.paravoid`. Plugin IDs and Java package names do not change.

## Downstream configuration (Groovy)

Use one exact published tag or commit consistently. The placeholder below is not
an existing release. JitPack plugin-marker rewriting has not been assumed:
`useModule` resolves implementation JARs directly, retaining existing plugin IDs.

```groovy
// settings.gradle: pluginManagement must come first.
pluginManagement {
    def paravoidVersion = '<tag-or-commit>'
    repositories {
        maven {
            url = uri('https://jitpack.io')
            content { includeGroup('com.github.lelloman.paravoid-android') }
        }
        google(); mavenCentral(); gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        def modules = [
            'com.lelloman.paravoid': 'paravoid-gradle-plugin',
            'com.lelloman.paravoid.module': 'paravoid-gradle-plugin',
            'com.lelloman.paravoid.shell': 'paravoid-gradle-plugin',
            'com.lelloman.paravoid.hilt': 'paravoid-hilt',
            'com.lelloman.paravoid.work': 'paravoid-work'
        ]
        if (modules.containsKey(requested.id.id)) {
            useModule("com.github.lelloman.paravoid-android:${modules[requested.id.id]}:${paravoidVersion}")
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven {
            url = uri('https://jitpack.io')
            content { includeGroup('com.github.lelloman.paravoid-android') }
        }
    }
}
```

```groovy
// app/build.gradle; ordinary Paravoid configuration still applies.
plugins { id 'com.lelloman.paravoid' }
dependencies {
    implementation 'com.github.lelloman.paravoid-android:paravoid-runtime:<tag-or-commit>'
}
```

API, contract, main plugin, Hilt and Work modules are separately available under
the same group/version. Optional plugin integrations retain their existing
configuration requirements; publishing does not change Hilt/Work support scope.

## Local verification and actual release boundary

```sh
# Set ANDROID_HOME; optional Gradle/cache/offline variables as in README.md.
bash release-tests/jitpack-consumer.sh
```

This runs the actual JitPack install script into a fresh isolated Maven directory,
then builds normal APK, complete shell APK and VPK from a standalone consumer.
It resolves the main/Hilt/Work plugin implementation JARs and their transitive
dependencies without composite-build substitution in the consumer. Hilt/Work
are resolved but not applied; functional coverage remains in their suites.
This is local coordinate/build evidence, not a successful remote JitPack build.

Before public release, select a license and release tag (or explicitly approve a
commit-based test publication), ensure all needed sources are on GitHub, then
authorize the remote push/build. Check JitPack's build log and download all six
modules, and rerun the standalone consumer against `https://jitpack.io` with that
exact version. Do not mark remote distribution complete until that passes.
Never move/reuse a released tag. Fucina CI may run these same local checks; an
internal package-registry deployment needs its actual URL/authentication policy
and is not silently configured by this change.

References: [JitPack build/custom commands and module coordinates](https://docs.jitpack.io/building/),
[Android publishing](https://docs.jitpack.io/android/).

## Recorded local evidence

2026-09-23: six-module JitPack-coordinate publication plus standalone normal/shell/
VPK consumer passed (`/tmp/paravoid-jitpack-consumer.log`, retained repository
`/tmp/paravoid-jitpack-maven.volrfj`). Original internal-coordinate publication and
consumer also passed (`/tmp/paravoid-internal-after-jitpack.log`, repository
`/tmp/paravoid-local-publication.KRN6Oc`). Four install-script safety/argument tests
pass: `python3 -m unittest discover -s release-tests -p test_jitpack.py`.
Local builds used cached Gradle 8.13/JDK 21/SDK 36; JitPack's configured JDK 17 and
SDK provisioning must still be verified on its runner. No push/tag/remote build,
phone operation or Fucina registry deployment occurred.
