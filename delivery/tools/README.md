# APK grant personalization

`bash delivery/tools/personalize.sh INPUT.apk OUTPUT.apk --grant /private/grant.json
--apksigner /path/to/Android/Sdk/build-tools/36.0.0/apksigner`

Run as one command. The grant is read from a file, never a command-line token.
The tool compiles and calls **A's `SignedMetadataVerifier`** for both the supplied
envelope and the record extracted by **B's `ApkGrantReader`** from the output APK.
It does not implement a competing grant parser. It verifies the developer v2/v3
signatures before and after insertion and requires identical signer certificate
digests and preserved schemes. It never re-signs or changes the input APK.

Only standalone non-ZIP64 v2/v3 APKs with known signing-block entries are supported.
Source stamps, v3.1/unknown block layouts and already provisioned inputs are rejected.
The tool's input limit is 1 GiB, signing-block limit 16 MiB, grant limit 16 KiB.
The candidate block ID remains `0x50564132`, subject to A's collision-review gate.

The output and fresh `.sha256` sidecar must not already exist. Candidates stay
private until verification completes. Publication is atomic for the APK; a crash
while writing the checksum can leave a verified APK with a missing/partial checksum,
which the distributor must regenerate before publication. Output mode is private
(0600), as a personalized APK contains an extractable bearer credential.

V4 regeneration needs the APK signing pipeline/private key and is **not supported
here**. An adjacent input `.idsig` is rejected, stale output sidecars are refused,
and no `.idsig` is copied. Distributors must never pair the output with an existing
v4 sidecar; v4-required delivery remains blocked until regeneration is integrated.

The CLI now reads trust/contract/audience from `assets/paravoid/shell-policy.json`
inside the developer-signature-verified APK, using `InstalledPolicyCodec`. It does
not accept an operator-supplied replacement policy. Missing/malformed policy fails
closed. This tool path supports HTTPS policies, not debug-HTTP personalization.
It checks grant issuedAt/expiry at personalization time as well as signature/scope;
delivery and lifecycle check time again later. It does not determine distributor
entitlement. The old six-argument Java verifier is retained only for isolated
stateless signature-vector tests, not used by the production CLI.

Host tests construct an ephemeral minimal Android APK, sign it with a throwaway
RSA-3072 key, personalize with A's checked-in grant vector, verify v2/v3 signatures,
and authenticate Java installed-carrier readback. An additional real CLI test uses
an embedded complete policy and freshly signed matching grant, proving the policy
is obtained from the APK rather than an operator argument. They do not install the APK or
claim Android package-replacement/v4 acceptance. Keys and APKs live only in `/tmp`.
