package com.lelloman.paravoidandroid.contract;

import org.junit.Test;
import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Test public void immutableScopesPreserveAbiOrder() {
        List<String> abis = new ArrayList<>(Arrays.asList("x86_64", "x86"));
        RequestScope scope = new RequestScope("example.app", "a".repeat(64), "stable", 30, abis, 1);
        abis.clear();
        assertEquals(Arrays.asList("x86_64", "x86"), scope.abis);
        assertThrows(UnsupportedOperationException.class, () -> scope.abis.clear());
        assertEquals(scope, new RequestScope("example.app", "a".repeat(64), "stable", 30, scope.abis, 1));
        assertNotEquals(scope, new RequestScope("example.app", "a".repeat(64), "stable", 30, Arrays.asList("x86", "x86_64"), 1));
    }
    @Test public void verifiedBytesAreNotMutableAndCredentialsAreRedacted() {
        byte[] body = {1, 2}, envelope = {3, 4};
        VerifiedHead head = new VerifiedHead(null, 1, 2, 3, HeadStatus.NO_COMPATIBLE_RELEASE,
            null, "head", body, envelope);
        body[0] = 9; envelope[0] = 9; head.body()[0] = 9; head.envelope()[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, head.body());
        assertArrayEquals(new byte[]{3, 4}, head.envelope());
        VerifiedGrant grant = new VerifiedGrant("app", "contract", "audience", "grant", "key",
            "issuer", "secret", 10, 20, envelope);
        assertFalse(grant.toString().contains("secret"));
        CredentialScope credential = CredentialScope.provisioned(grant);
        assertEquals(grant.scopeId, credential.id);
        assertEquals(20, credential.expiresAt);
        assertEquals("public", CredentialScope.publicAccess().id);
    }
    @Test public void admissionStatusCannotContradictItsOffer() {
        ExpectedArchive archive = new ExpectedArchive("r1", 1, "a".repeat(64), "b".repeat(64), 50);
        assertThrows(IllegalArgumentException.class, () -> new AdmissionResult(HeadStatus.AVAILABLE, null, archive));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionResult(HeadStatus.NO_COMPATIBLE_RELEASE, new AdmissionId("a"), archive));
        assertEquals(archive, new AdmissionResult(HeadStatus.AVAILABLE, new AdmissionId("a"), archive).release);
    }
}
