package com.lelloman.paravoidandroid.contract;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Generates review candidates only. Never overwrites accepted checked-in vectors. */
public final class MetadataVectorGenerator {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]); Files.createDirectories(output);
        MetadataTestSupport f = new MetadataTestSupport();
        Files.write(output.resolve("trust.json"), f.trustBytes());
        Files.write(output.resolve("head-a.json"), f.head(f.headBody(1)));
        Files.write(output.resolve("head-b.json"), f.head(f.headBody(2)));
        Files.write(output.resolve("grant.json"), f.grant(f.grantBody()));
        Files.write(output.resolve("head-wrong-role.json"), MetadataTestSupport.signed("grant", "head", f.head.getPrivate(), StrictJson.canonical(f.headBody(1))));
        String duplicate = new String(StrictJson.canonical(f.headBody(1)), StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":1,\"version\":1");
        Files.write(output.resolve("head-duplicate.json"), MetadataTestSupport.signed("head", "head", f.head.getPrivate(), duplicate.getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> wrong = f.headBody(1); wrong.put("sdk", 31);
        Files.write(output.resolve("head-wrong-scope.json"), f.head(wrong));
        Map<String,Object> audience = f.grantBody(); audience.put("audience", "https://wrong.example/");
        Files.write(output.resolve("grant-wrong-audience.json"), f.grant(audience));
        List<Object> cases = new ArrayList<>();
        for (String name : Arrays.asList("head-a", "head-b", "grant")) cases.add(entry(name, "ACCEPT"));
        cases.add(entry("head-wrong-role", "INVALID_SIGNATURE")); cases.add(entry("head-duplicate", "MALFORMED"));
        cases.add(entry("head-wrong-scope", "INCOMPATIBLE")); cases.add(entry("grant-wrong-audience", "INCOMPATIBLE"));
        Files.write(output.resolve("cases.json"), StrictJson.canonical(cases));
        // Private fixture signing keys intentionally are not written or retained.
    }
    private static Map<String,Object> entry(String name, String result) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("file", name + ".json"); value.put("role", name.startsWith("head") ? "head" : "grant");
        value.put("result", result); return value;
    }
}
