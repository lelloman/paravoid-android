package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.file.*;
import java.util.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;

public final class SignedDeliveryTest {
    public static void main(String[] args) throws Exception {
        Path vectors = Paths.get("../paravoid-contract/src/test/resources/metadata-vectors");
        SignedMetadataVerifier verifier = new SignedMetadataVerifier();
        TrustPolicy trust = verifier.readTrustPolicy(Files.readAllBytes(vectors.resolve("trust.json")));
        for (Authentication mode : Authentication.values()) try (Fixture f = new Fixture()) {
            ShellPolicy policy = new ShellPolicy(trust.applicationId, "a".repeat(64), trust,
                    "https://updates.example.test/", "stable", mode, Bootstrap.EMBEDDED,
                    true, false, 1, Collections.emptyMap(), new byte[0]);
            RequestScope scope = new RequestScope(policy.applicationId, policy.shellContractId, "stable", 30,
                    Collections.singletonList("x86_64"), 1);
            DeliveryClientTest.Clock clock = new DeliveryClientTest.Clock(); clock.wall = 1800000000;
            DeliveryClientTest.Life life = new DeliveryClientTest.Life(); life.clock = clock;
            DeliveryClient client = new DeliveryClient(policy, verifier, life, clock, f.dir,
                    url -> { f.requests++; return f.responses.remove(); }, (directory, size) -> { throw new AssertionError("metadata fixtures cannot authorize a real archive test"); });
            client.installedCredential(mode == Authentication.PUBLIC ? null : Files.readAllBytes(vectors.resolve("grant.json")));
            byte[] head = Files.readAllBytes(vectors.resolve("head-a.json"));
            String etag = "\"" + HttpTransport.hash(head) + "\"";
            Fake response = new Fake(200, head).put("Content-Type", "application/json").put("ETag", etag);
            f.responses.add(response); client.check(scope, false, false);
            check(life.observations == 1);
            check(response.getRequestProperty("Authorization") == null == (mode == Authentication.PUBLIC));
            f.responses.add(new Fake(304, new byte[0]).put("ETag", etag));
            client.check(scope, false, false); check(life.observations == 2 && life.stages == 0);
            for (String name : new String[] {"head-duplicate.json", "head-wrong-role.json", "head-wrong-scope.json"}) {
                byte[] invalid = Files.readAllBytes(vectors.resolve(name));
                f.responses.add(new Fake(200, invalid).put("Content-Type", "application/json")
                        .put("ETag", "\"" + HttpTransport.hash(invalid) + "\""));
                try { client.check(scope, false, false); throw new AssertionError("accepted " + name); }
                catch (ContractException expected) { check(life.observations == 2); }
            }
            if (mode == Authentication.APK_KEY) {
                DeliveryClientTest.contractFailure(ContractException.Code.INCOMPATIBLE,
                        () -> client.installedCredential(Files.readAllBytes(vectors.resolve("grant-wrong-audience.json"))));
                check(life.credential == null);
            }
        }
        System.out.println("SignedDeliveryTest: " + TransportTest.assertions + " assertions passed");
    }
}
