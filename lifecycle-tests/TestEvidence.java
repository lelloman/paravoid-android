package com.lelloman.paravoidandroid.contract;

import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.charset.StandardCharsets;

/** Test-source-only evidence factory. Never included in runtime or contract production output. */
public final class TestEvidence {
    public static VerifiedHead head(RequestScope scope, long revision, long issued, long expires,
            ExpectedArchive release, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new VerifiedHead(scope, revision, issued, expires,
            release == null ? HeadStatus.NO_COMPATIBLE_RELEASE : HeadStatus.AVAILABLE,
            release, "test", bytes, bytes);
    }
    public static VerifiedRelease release(ExpectedArchive identity, java.util.List<InventoryEntry> inventory) {
        return new VerifiedRelease("app", "contract", "test", "a".repeat(64), identity, 1, 30, 0,
            java.util.Collections.emptyList(), inventory, new byte[] {1});
    }
    public static CredentialScope credential(String id, long issued, long expires) {
        return CredentialScope.provisioned(new VerifiedGrant("app", "contract", "https://test/", "grant", "key",
            "test", "TEST-ONLY", issued, expires, id.getBytes(StandardCharsets.UTF_8)));
    }
}
