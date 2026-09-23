# Independent v1 review handoff

This is review input, **not security approval**. No independent reviewer has
signed off. Review against a named final commit, not an earlier passing build.

## Authoritative surfaces

- `V1.md`: wire format, trust, replay, installed boundary and failure contracts.
- `paravoid-contract`: strict metadata/ZIP parsing, signature verification,
  pinned installed policy and VPK producer.
- `paravoid-runtime/.../lifecycle`: durable admission, selection, generations,
  space ownership and process leases.
- `delivery/src`: HTTP/cache/resume, credential partitions, cancellation and
  scheduling. `delivery/android/src`: installed APK authority and recovery UI.
- `delivery/tools`: APK personalization and verification CLI.

## Decisions needed before wire freeze/public release

1. Review candidate APK Signing Block ID `0x50564132` for ecosystem collisions.
   It is private and unregistered; passing our own parser does not establish
   global uniqueness. Resolve a collision by changing both specification and
   implementation, then repeat personalization/package-replacement tests.
2. Approve the explicit v4 disposition: the CLI rejects a supplied adjacent
   `.idsig`; it does not regenerate v4 signatures. Distributors requiring v4
   cannot treat current personalization as a complete publishing pipeline.
3. Review the signing/key operations for the actual distributor: root custody,
   role separation, allowed origins, grant entitlement, revocation, APK credential
   replacement and incident response. Fixture keys/CA are never production inputs.
4. Audit the implementation and negative vectors below; record findings, fixes,
   exclusions and the exact reviewed commit. Automated tests are supporting
   evidence, not a substitute for this review.

## Minimum attack/failure questions

- Can malformed JSON, duplicate fields, integer/length overflow, noncanonical
  envelopes, unsupported algorithms or ambiguous ZIP structures reach user code?
- Are every component byte, nested container, resource reservation and installed
  compatibility field bound to the correct signed release and shell contract?
- Can stale heads, cache responses, ranges/ETags, credential changes, redirected
  origins or mixed signed artifacts bypass verification or entitlement?
- Can process death, partial publication, competing controllers, cancellation or
  cleanup reset replay floors, clear quarantine, delete leased bytes or activate
  a generation without a coordinated cold start?
- Are grant/error/log surfaces free of bearer-key disclosure? A grant is a bearer
  credential embedded in an APK, **not a secret from the device owner**, hardware
  attestation or proof of an unmodified client. Cloning/extraction risk must be
  acceptable to the distributor. Revocation does not erase a usable offline app.
- Does personalization preserve supported developer signatures while refusing
  ambiguous/duplicate grant blocks and unsupported signing layouts?
- Do recovery entry points require confirmation for destructive actions and avoid
  loading payload code or acquiring payload leases? OS service stalls and
  process creation are not made atomic by a UI timeout or PID snapshot.

## Evidence boundaries

Start with `RELEASE-READINESS.md`, `release-tests/README.md`,
`delivery/device-tests/HTTPS-RELEASE.md`, `delivery/PERSISTENCE-TESTS.md` and
`integration-v1/STARTUP-FAILURES.md`. They separate host, installed x86_64, TLS,
fault-injection and historical results. Physical ARM64, actual distributor
infrastructure and final real-app workflows remain distinct acceptance gates.
Process death does not establish physical power-loss durability. No production
artifact upload, tag or publication is implied by this handoff.
