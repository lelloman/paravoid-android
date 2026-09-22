package com.lelloman.paravoidandroid.contract;

import java.io.InputStream;
import java.util.*;
import org.junit.Test;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static org.junit.Assert.*;

public class SharedMetadataVectorsTest {
    static byte[] resource(String name) throws Exception {
        try (InputStream input = SharedMetadataVectorsTest.class.getResourceAsStream("/metadata-vectors/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    @Test public void checkedInVectorsHaveDeclaredOutcomes() throws Exception {
        SignedMetadataVerifier verifier = new SignedMetadataVerifier();
        TrustPolicy trust = verifier.readTrustPolicy(resource("trust.json"));
        ShellPolicy policy = MetadataTestSupport.policy(trust);
        RequestScope scope = new RequestScope(policy.applicationId, policy.shellContractId, "stable", 30,
            Collections.singletonList("x86_64"), 1);
        List<?> cases = (List<?>) StrictJson.parse(resource("cases.json"), 65536);
        assertEquals(7, cases.size());
        for (Object item : cases) {
            Map<?,?> vector = (Map<?,?>)item;
            byte[] envelope = resource((String)vector.get("file"));
            String actual = "ACCEPT";
            try {
                if (vector.get("role").equals("head")) verifier.verifyHead(envelope, policy, scope);
                else verifier.verifyGrant(envelope, policy);
            } catch (ContractException error) { actual = error.code.name(); }
            assertEquals(vector.get("file").toString(), vector.get("result"), actual);
        }
        VerifiedHead a = verifier.verifyHead(resource("head-a.json"), policy, scope);
        VerifiedHead b = verifier.verifyHead(resource("head-b.json"), policy, scope);
        assertEquals(a.scope, b.scope);
        assertTrue(b.headRevision > a.headRevision);
        assertTrue(b.release.payloadVersion > a.release.payloadVersion);
    }
}
