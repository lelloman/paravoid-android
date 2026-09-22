# Python transport reference

`python3 delivery/reference/server.py /private/path/catalog.json --port 8080`
binds loopback HTTP only. Debug clients must explicitly opt into HTTP. HTTPS is
required for delivery builds; TLS deployment/certificates are outside this local
reference. Do not expose Python's development HTTP server directly to the internet.

Supply **pre-signed, verified** head envelopes and immutable VPK files. This server
does not parse/sign grants, heads or VPKs, select the newest upload, or mint an
unsigned absence response. An unconfigured request scope returns 404. Fixtures in
the host tests deliberately contain opaque non-executable bytes, not conforming
security vectors. A's shared vectors will supply the real signed bytes.

Example private operator configuration (paths relative to the configuration):

```json
{
  "authentication": "public",
  "prefix": "/updates/",
  "heads": [{
    "applicationId": "example.app",
    "query": {
      "contract": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "channel": "stable", "sdk": "30", "abis": "x86_64,x86",
      "runtime": "1", "format": "1", "protocol": "1"
    },
    "file": "head.json"
  }],
  "archives": [{"applicationId": "example.app", "releaseId": "r1", "file": "payload.vpk"}],
  "grants": []
}
```

For `apkKey`, each private `grants` entry contains `key` (43-character provisioned
Bearer token), `applicationId`, permitted `channels` and permitted `releases`.
This is server-side test authorization data, **not** the signed APK grant schema.
Keep config outside source control and restrict its filesystem permissions. There
are no public upload, grant-management, account, payment or store-specific routes.
Authorization runs before head/304/full/range responses. `Catalog.revoke` is an
in-process test/admin seam; it stops future requests, not an authorized stream.
Changing configuration requires restarting the reference server. Production grant
expiry and entitlement synchronization remain the distributor's responsibility.

Publish complete durable archives before their corresponding heads. Do not mutate
configured archive files in place. The server hashes them at startup, streams with
bounded buffers, and handles disappearance with 404. Every client must still
verify signatures, scope, freshness, size and full hashes. TLS/signature conformance,
personalized APKs and Android lifecycle integration are separate gates.
