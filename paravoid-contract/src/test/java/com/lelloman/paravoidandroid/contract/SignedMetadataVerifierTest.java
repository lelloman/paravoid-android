package com.lelloman.paravoidandroid.contract;

import org.junit.BeforeClass;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;
import static org.junit.Assert.*;

public class SignedMetadataVerifierTest {
    static MetadataTestSupport fixture;
    final SignedMetadataVerifier verifier = new SignedMetadataVerifier();
    @BeforeClass public static void keys() throws Exception { fixture = new MetadataTestSupport(); }
    @Test public void verifiesOffersAndExactBytesButDoesNotPretendToAdmitTime() throws Exception {
        byte[] canonical = StrictJson.canonical(fixture.headBody(1));
        byte[] body = (" \n" + new String(canonical, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] signed = MetadataTestSupport.signed("head", "head", fixture.head.getPrivate(), body);
        VerifiedHead head = verifier.verifyHead(signed, fixture.policy, fixture.scope);
        assertArrayEquals(body, head.body());
        assertEquals(Digests.sha256(signed), head.envelopeSha256);
        assertEquals(1, head.release.payloadVersion);
        assertEquals(2, verifier.verifyHead(fixture.head(fixture.headBody(2)), fixture.policy, fixture.scope).release.payloadVersion);
        Map<String,Object> old = fixture.headBody(1); old.put("issuedAt", 1); old.put("expiresAt", 2);
        assertEquals(2, verifier.verifyHead(fixture.head(old), fixture.policy, fixture.scope).expiresAt);
        // Authentication is not temporal/replay admission: Lifecycle must reject stale heads.
    }
    @Test public void verifiesBothAuthenticatedNonAvailableStatuses() throws Exception {
        for (String status : Arrays.asList("no-compatible-release", "shell-update-required")) {
            Map<String,Object> body = fixture.headBody(1); body.put("status", status); body.put("release", null);
            assertNull(verifier.verifyHead(fixture.head(body), fixture.policy, fixture.scope).release);
            body.put("release", fixture.headBody(1).get("release"));
            assertCode(MALFORMED, () -> verifier.verifyHead(fixture.head(body), fixture.policy, fixture.scope));
        }
    }
    @Test public void rejectsSignatureRoleScopeAndSchemaConfusion() throws Exception {
        assertCode(INVALID_SIGNATURE, () -> verifier.verifyHead(MetadataTestSupport.signed("grant", "head", fixture.head.getPrivate(),
            StrictJson.canonical(fixture.headBody(1))), fixture.policy, fixture.scope));
        assertCode(UNTRUSTED_KEY, () -> verifier.verifyHead(MetadataTestSupport.signed("head", "unknown", fixture.head.getPrivate(),
            StrictJson.canonical(fixture.headBody(1))), fixture.policy, fixture.scope));
        Map<String,Object> wrong = fixture.headBody(1); wrong.put("sdk", 31);
        assertCode(INCOMPATIBLE, () -> verifier.verifyHead(fixture.head(wrong), fixture.policy, fixture.scope));
        Map<String,Object> extra = fixture.headBody(1); extra.put("unexpected", true);
        assertCode(MALFORMED, () -> verifier.verifyHead(fixture.head(extra), fixture.policy, fixture.scope));
        String duplicate = new String(StrictJson.canonical(fixture.headBody(1)), StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":1,\"version\":1");
        assertCode(MALFORMED, () -> verifier.verifyHead(MetadataTestSupport.signed("head", "head", fixture.head.getPrivate(),
            duplicate.getBytes(StandardCharsets.UTF_8)), fixture.policy, fixture.scope));
    }
    @Test public void rejectsBoundsAndInvalidIntervalsBeforeAdmission() throws Exception {
        Map<String,Object> body = fixture.headBody(1); body.put("expiresAt", 1800086401L);
        assertCode(MALFORMED, () -> verifier.verifyHead(fixture.head(body), fixture.policy, fixture.scope));
        body.put("expiresAt", 1800000000L);
        assertCode(MALFORMED, () -> verifier.verifyHead(fixture.head(body), fixture.policy, fixture.scope));
        assertCode(LIMIT_EXCEEDED, () -> verifier.verifyHead(new byte[Protocol.MAX_HEAD_BYTES + 1], fixture.policy, fixture.scope));
        Map<String,Object> giant = fixture.headBody(1); giant.put("applicationId", "a".repeat(4097));
        assertCode(LIMIT_EXCEEDED, () -> verifier.verifyHead(fixture.head(giant), fixture.policy, fixture.scope));
    }
    @Test public void grantsAreBoundAndNeverRenderedWithSecrets() throws Exception {
        VerifiedGrant grant = verifier.verifyGrant(fixture.grant(fixture.grantBody()), fixture.policy);
        assertEquals("A".repeat(43), grant.bearerKey());
        assertFalse(grant.toString().contains(grant.bearerKey()));
        assertEquals(grant.scopeId, CredentialScope.provisioned(grant).id);
        Map<String,Object> wrong = fixture.grantBody(); wrong.put("audience", "https://evil.example/");
        assertCode(INCOMPATIBLE, () -> verifier.verifyGrant(fixture.grant(wrong), fixture.policy));
        wrong.put("audience", fixture.policy.baseUrl); wrong.put("key", "A".repeat(42) + "B");
        assertCode(MALFORMED, () -> verifier.verifyGrant(fixture.grant(wrong), fixture.policy));
    }
    @Test public void trustRolesAreSeparateAndProfileRestricted() throws Exception {
        TrustPolicy parsed = verifier.readTrustPolicy(fixture.trustBytes());
        assertArrayEquals(fixture.head.getPublic().getEncoded(), parsed.headKeys.get("head").getEncoded());
        TrustPolicy shared = new TrustPolicy(parsed.applicationId, parsed.releaseKeys, parsed.releaseKeys, parsed.grantKeys, 1, 1);
        assertCode(INCOMPATIBLE, () -> verifier.verifyHead(fixture.head(fixture.headBody(1)), MetadataTestSupport.policy(shared), fixture.scope));
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        TrustPolicy weak = new TrustPolicy(parsed.applicationId, Collections.singletonMap("release", generator.generateKeyPair().getPublic()),
            parsed.headKeys, parsed.grantKeys, 1, 1);
        assertCode(INCOMPATIBLE, () -> verifier.verifyHead(fixture.head(fixture.headBody(1)), MetadataTestSupport.policy(weak), fixture.scope));
    }
    interface Checked { void run() throws Exception; }
    static void assertCode(ContractException.Code code, Checked action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ContractException failure) { assertEquals(code, failure.code); }
    }
}
