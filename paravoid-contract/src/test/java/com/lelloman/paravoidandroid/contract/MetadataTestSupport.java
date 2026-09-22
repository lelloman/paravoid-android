package com.lelloman.paravoidandroid.contract;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

final class MetadataTestSupport {
    final KeyPair release, head, grant;
    final TrustPolicy trust;
    final ShellPolicy policy;
    final RequestScope scope;
    MetadataTestSupport() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        release = generator.generateKeyPair(); head = generator.generateKeyPair(); grant = generator.generateKeyPair();
        trust = new TrustPolicy("example.app.paravoid", Collections.singletonMap("release", release.getPublic()),
            Collections.singletonMap("head", head.getPublic()), Collections.singletonMap("grant", grant.getPublic()), 1, 1);
        policy = policy(trust);
        scope = new RequestScope(policy.applicationId, policy.shellContractId, policy.channel, 30,
            Collections.singletonList("x86_64"), Protocol.RUNTIME_ABI);
    }
    static ShellPolicy policy(TrustPolicy trust) {
        return new ShellPolicy(trust.applicationId, "a".repeat(64), trust, "https://updates.example.test/",
            "stable", Authentication.APK_KEY, Bootstrap.EMBEDDED, true, false, 1, Collections.emptyMap(), new byte[0]);
    }
    Map<String,Object> headBody(long version) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("version", 1); body.put("applicationId", policy.applicationId); body.put("shellContractId", policy.shellContractId);
        body.put("channel", "stable"); body.put("sdk", 30); body.put("abis", scope.abis); body.put("runtimeAbi", 1);
        body.put("formatVersion", 1); body.put("headRevision", version); body.put("issuedAt", 1800000000L);
        body.put("expiresAt", 1800003600L); body.put("status", "available");
        Map<String,Object> offer = new LinkedHashMap<>();
        offer.put("releaseId", "release-" + version); offer.put("payloadVersion", version);
        offer.put("manifestSha256", (version == 1 ? "b" : "c").repeat(64));
        offer.put("archiveSha256", (version == 1 ? "d" : "e").repeat(64)); offer.put("archiveSize", 8192);
        body.put("release", offer); return body;
    }
    Map<String,Object> grantBody() {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("version", 1); body.put("applicationId", policy.applicationId); body.put("shellContractId", policy.shellContractId);
        body.put("audience", policy.baseUrl); body.put("grantId", "fixture-grant"); body.put("keyId", "fixture-credential");
        body.put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        body.put("issuedAt", 1800000000L); body.put("expiresAt", 0); return body;
    }
    byte[] head(Map<String,Object> body) throws Exception { return signed("head", "head", head.getPrivate(), StrictJson.canonical(body)); }
    byte[] grant(Map<String,Object> body) throws Exception { return signed("grant", "grant", grant.getPrivate(), StrictJson.canonical(body)); }
    static byte[] signed(String role, String keyId, PrivateKey key, byte[] body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key);
        signer.update(("paravoid/v1/" + role + "\n").getBytes(StandardCharsets.US_ASCII)); signer.update(body);
        Map<String,Object> envelope = new LinkedHashMap<>();
        envelope.put("keyId", keyId); envelope.put("body", Base64.getEncoder().encodeToString(body));
        envelope.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        return StrictJson.canonical(envelope);
    }
    byte[] trustBytes() throws Exception {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("version", 1); value.put("applicationId", trust.applicationId);
        value.put("releaseKeys", encoded(trust.releaseKeys)); value.put("headKeys", encoded(trust.headKeys));
        value.put("grantKeys", encoded(trust.grantKeys)); value.put("minimumPayloadVersion", 1); value.put("minimumHeadRevision", 1);
        return StrictJson.canonical(value);
    }
    static Map<String,String> encoded(Map<String,PublicKey> keys) {
        Map<String,String> result = new LinkedHashMap<>();
        keys.forEach((id,key) -> result.put(id, Base64.getEncoder().encodeToString(key.getEncoded()))); return result;
    }
}
