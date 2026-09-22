# Track B handoff, 2026-09-22

Worktree `/tmp/paravoid-v1-delivery`, branch `v1/delivery`, baseline `8c40885`.
No changes to another checkout, devices, store services or publishing.

## Shared commits

| Track A source | Cherry-pick here | Purpose |
| --- | --- | --- |
| `7ace172` | `050982e` | Immutable contracts / verification / lifecycle interfaces |
| `d58457f` | `73df1bb` | SignedMetadataVerifier, strict JSON and metadata vectors |

When integrating B into A, skip these two duplicate foundation commits. All B
changes live under `delivery/`; root/shared/runtime files are changed only by the
foundation cherry-picks. No permissive archive verifier or generation selector.

## Delivery commits

| Commit | Slice |
| --- | --- |
| `1bb9e9f` | Bounded HTTP / resumption with private fakes |
| `e753cf1` | Retry, cancellation and foreground policy |
| `30f0b85` | Store-independent Python HTTP reference / actual Java interop |
| `1648dec` | Shared metadata/admission/staging handoff |
| `2a7d7a7` | Installed APK grant carrier / restart-safe partials and auth suppression |
| `9b3808e` | Asynchronous controller, persisted preferences and Android UI |
| `e3d4c37` | Real signed vectors / verified v2-v3 personalization tool |

The subsequent hardening commit covers replacement-controller notification,
same-scope admission preservation, bounded abandoned partials and sanitized network
failure retries. Its hash is in the branch log/final report (not self-embedded).

## Confirmed boundaries

`DeliveryClient` authenticates through A, submits all heads to C (including 304
and non-available), and asks C to stage only after a complete size/hash-checked
transfer. C alone owns clocks/floors/replay, accepted storage, generation selection
and execution. B deletes its source after synchronous staging returns, including
failure. No source mutation during the handoff. Cancellation after handoff does
not interrupt C's publication transaction.

Ordinary same-grant initialization never publishes an intermediate null credential
to C: that could revoke another process's valid admission. Replacement publishes
the new verified scope; missing/bad extraction or verification publishes null.
Old callbacks cannot poison a newer credential session. Delivery's persisted scope
marker/auth suppression are cache/control data, never replay authority. A fresh
same-grant process preserves partials and suppression. Grant/contract replacement
clears them without touching C's history or accepted payloads.

B uses a private transfer lock, never C's selection lock, to prevent concurrent
writers. The client retains at most one partial candidate. Android lease/selection
proof remains entirely C's work.

## Validation

- `bash delivery/test.sh`: 259 Java assertions (including real signed vectors and
  six real Java/Python HTTP assertions), 8 Python HTTP tests, 3 personalization tests.
- Personalization tests build a minimal binary-manifest APK with aapt2, sign with
  an ephemeral RSA-3072 key, preserve v2/v3 certificate/scheme verification, and
  authenticate Java carrier readback using A's verifier. No emulator/phone involved.
- `bash delivery/check-android.sh`: shell UI/core source compilation on Android 36.
- `git diff --check`: clean.
- JDK 21 compiling with Java 11 target, Python 3.12, SDK platform 36/build-tools
  36.0.0. Only warning: existing shared ContractException serialVersionUID.

## Remaining coordination and acceptance gates

1. **A build/policy integration:** include B core/Android sources with the contract
   module, supply APK-pinned policy, guarantee debug HTTP cannot ship in release,
   and define policy export/carrier for personalization. The tool currently needs
   operator-supplied policy matching the APK; it cannot independently prove that
   equality before the carrier is defined. Coordinate INTERNET/network-state and
   shell Activity declarations with contract generation.
2. **C routing:** initialize the controller and route to `ShellUpdatesActivity` in
   a shell-only process, with no payload Application/providers/lease. Feed normal
   foreground/empty bootstrap hooks and APK replacement notifications. B did not
   edit runtime factories, manifest routing or process selection.
3. **Storage interface:** no C reservation/optional-history-eviction API exists.
   Current preflight conservatively requires 2x signed archive size + 2 GiB maximum
   materialization + 64 MiB. This is a free-space check, not a durable OS reservation,
   and can reject updates that a precise inventory-based estimate would allow.
4. **Recovery action detail:** snapshot has availability/active but no explicit
   retryable quarantined identity. UI offers confirmed retry for recovery's active
   identity; C must reject corrupt/non-quarantined/stale identities. A clearer
   actionable identity could improve this without B inferring C's state.
5. **VPKs/device gates:** real archive verifier/executable A/B fixtures, Android
   backup exclusions, installed grant readback/replacement/revocation, offline
   accepted-payload operation, recovery routing and API 30/36.1 device tests remain.
   Metadata A/B vectors are not payloads and never pass the download-to-stage tests.
6. **Personalization support:** candidate ID collision review, unsupported signing
   layouts (including source stamps/v3.1), v4 regeneration/checksum interruption and
   distributor transport preservation remain gates. The tool refuses unsupported
   layouts and existing v4 sidecars; it never silently reuses them.

The branch is ready for integration review, not a claim that Paravoid v1 is shipped
or that the cross-track/device/security gates have passed.
