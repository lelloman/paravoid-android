# Complete VPK producer

The consolidated plugin exposes an explicit complete-VPK producer. This is not yet
the complete shell product: normal `assemble` and the experimental resource-shell
task still use their existing startup paths. Do not publish the generated VPK for
those older shells. APK policy embedding, complete-generation early loading and
installed-device update/recovery validation remain integration gates.

```groovy
paravoid {
    payloadVersion = 1L
    releaseId = 'release-1' // defaults to p<payloadVersion>
    bootstrap = 'embedded' // or empty; empty requires enabled updates
    updates {
        enabled = true
        baseUrl = 'https://updates.example/'
        channel = 'stable'
        authentication = 'public' // or apkKey; no credential in this DSL
        trustPolicyFile = layout.projectDirectory.file('paravoid/trust.json')
    }
    signing {
        keyId = 'release-key'
        privateKeyFile = layout.projectDirectory.file('private/release.pk8')
    }
}
```

The trust file is the public v1 trust policy defined in `V1.md`. Release keys are
RSA-3072, exponent 65537; the private file accepts unencrypted PKCS#8 DER or PEM.
Keep it outside version control. It is not a task cache input or archive entry;
signing always runs. APK and payload signing keys are separate.

For a variant such as `paravoidAndroidDebug`, run
`packageParavoidAndroidDebugParavoidVpk`. It builds the actual D8 payload, complete
resource/asset APK, merged Java-resource JAR, native libraries and resource ledger;
signs and verifies them with the shared complete-VPK verifier before publishing
`build/outputs/paravoid/<variant>/payload.vpk`. Adjacent outputs are the exact
signed `release.json`, `payload.vpk.sha256`, and a public `vpk-report.json`.

`generate<Variant>ParavoidCompletePolicy` produces the public shell policy.
`export<Variant>ParavoidCompleteBaseline` exports review candidates into
`complete-baseline-candidate/`. Copy the reviewed ledger, boundary and
`shell-contract.json` into `<baselineDirectory>/<variant>/` and configure
`paravoid.baselineDirectory`. Subsequent VPK builds depend on
`check<Variant>ParavoidVpkContract`: payload changes can proceed, but changes to
the installed manifest, pinned resources, runtime, trust or distribution policy
require a new shell generation. An embedded-profile baseline is not a complete
baseline and cannot be silently promoted.

Minimum SDK is 30 for this resource/policy pipeline. Shrinking, core-library
desugaring and split APK inputs remain unsupported. HTTP is opt-in only for
debuggable builds; a `release` build type additionally rejects the debug HTTP
flag even if someone marks that build type debuggable. Normal APK output is not
modified by these explicit producer tasks.
