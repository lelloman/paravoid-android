package com.lelloman.paravoidandroid.contract;

import java.nio.file.*;
import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Public-policy fixture only; production never accepts this factory as installed authority. */
public final class PolicyFixtures {
    public static byte[] policy(Authentication authentication, String url) throws Exception {
        return policy(authentication, url, Files.readAllBytes(Paths.get("../paravoid-contract/src/test/resources/metadata-vectors/trust.json")));
    }
    private static byte[] policy(Authentication authentication, String url, byte[] trust) throws Exception {
        Map<String,Object> d = new LinkedHashMap<>(); d.put("profile", "embedded-apk-v1"); d.put("applicationId", "example.app.paravoid");
        d.put("minSdk", 30); d.put("manifestSha256", "b".repeat(64)); d.put("declarations", Collections.emptyMap());
        d.put("pinnedResources", Collections.emptyMap()); d.put("runtimeClasses", Collections.singletonMap("runtime.class", "c".repeat(64)));
        d.put("nativeAbis", Collections.emptyMap()); d.put("ledgerReservations", Collections.emptyMap());
        d.put("apkSigners", Collections.singletonList("d".repeat(64))); d.put("toolchain", Collections.singletonMap("agp", "8.13.2"));
        Map<String,Object> distribution = new LinkedHashMap<>(); distribution.put("bootstrap", "embedded"); distribution.put("updates", false); d.put("distribution", distribution);
        Map<String,Object> wrapper = new LinkedHashMap<>(); wrapper.put("version", 1); wrapper.put("descriptor", d); wrapper.put("contractId", Digests.sha256(StrictJson.canonical(d)));
        return InstalledPolicyCodec.create(StrictJson.canonical(wrapper), trust, Bootstrap.EMBEDDED, true, url, "stable", authentication, false, false);
    }
    /** Ephemeral signed APK-policy/grant pair for personalization tests, never release infrastructure. */
    public static void main(String[] args) throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        java.security.KeyPair release = generator.generateKeyPair(), head = generator.generateKeyPair(), grant = generator.generateKeyPair();
        Map<String,Object> trust = new LinkedHashMap<>(); trust.put("version", 1); trust.put("applicationId", "example.app.paravoid");
        trust.put("releaseKeys", Collections.singletonMap("release", Base64.getEncoder().encodeToString(release.getPublic().getEncoded())));
        trust.put("headKeys", Collections.singletonMap("head", Base64.getEncoder().encodeToString(head.getPublic().getEncoded())));
        trust.put("grantKeys", Collections.singletonMap("grant", Base64.getEncoder().encodeToString(grant.getPublic().getEncoded())));
        trust.put("minimumPayloadVersion", 1); trust.put("minimumHeadRevision", 1);
        byte[] bytes = policy(Authentication.APK_KEY, "https://updates.example.test/", StrictJson.canonical(trust));
        Path target = Paths.get(args[0]).resolve("paravoid/shell-policy.json"); Files.createDirectories(target.getParent()); Files.write(target, bytes);
        ShellPolicy policy = InstalledPolicyCodec.read(bytes, false);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("version", 1); body.put("applicationId", policy.applicationId);
        body.put("shellContractId", policy.shellContractId); body.put("audience", policy.baseUrl); body.put("grantId", "fixture"); body.put("keyId", "fixture-key");
        body.put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        body.put("issuedAt", System.currentTimeMillis() / 1000); body.put("expiresAt", 0);
        byte[] encoded = StrictJson.canonical(body);
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA"); signer.initSign(grant.getPrivate());
        signer.update("paravoid/v1/grant\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); signer.update(encoded);
        Map<String,Object> envelope = new LinkedHashMap<>(); envelope.put("keyId", "grant");
        envelope.put("body", Base64.getEncoder().encodeToString(encoded)); envelope.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        Files.write(Paths.get(args[1]), StrictJson.canonical(envelope));
    }
}
