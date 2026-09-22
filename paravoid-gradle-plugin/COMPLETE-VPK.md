# Complete VPK producer

The plugin exposes a complete-VPK producer and opt-in automatic complete-shell
assembly. Set `packaging = 'complete'` to make shell `assemble` consume the signed
VPK, pin its policy in the APK, and select the verified-generation startup path.
`bootstrap = 'empty'` omits the VPK and does not require its private signing key
to assemble the shell. Normal APK/AAB variants and default `dexOnly` behavior are
unchanged. Complete shell AAB output is rejected.

Build-level tests cover signed embedded/empty output and absence of movable
fallback content. Installed-device startup/update/recovery acceptance is still a
separate gate; this is not yet a release-readiness claim.

```groovy
paravoid {
    packaging = 'complete'
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
signed `release.json`, `payload.sha256`, and a public `vpk-report.json`.
`payload.vpk.sha256` remains an identical compatibility alias for existing fixtures.

`report<Variant>ParavoidPackaging` exports root-level `shell-contract.json`,
`resource-ledger.json`, and `packaging-report.json`/`.txt`. It describes pinned
roots and their dependency chains, movable resources, shell/payload ownership,
public trust key IDs, unsupported features and the accepted-baseline diff. It
does not read private signing keys or grants. Complete assembly, VPK compatibility
checks and complete-mode baseline export generate this evidence automatically.
An incompatible build writes the report before its contract gate fails; a report
is not approval to publish the candidate or proof of installed-device behavior.

Automatic output is `outputs/paravoid/<variant>/shell.apk`, also exposed through
AGP's transformed APK artifact (used by its install tasks). The intermediate
pre-transform APK is not the distribution artifact. The transform uses AGP's
[multi-artifact API](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/artifact/ArtifactTransformationRequest)
to preserve output metadata and an independent analysis snapshot to avoid cycles.

`generate<Variant>ParavoidCompletePolicy` produces the public shell policy.
`export<Variant>ParavoidCompleteBaseline` exports review candidates into
`complete-baseline-candidate/`. In complete mode, ordinary `export<Variant>ParavoidBaseline`
also exports the complete contract in `baseline-candidate/`. Copy the reviewed ledger, boundary and
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
