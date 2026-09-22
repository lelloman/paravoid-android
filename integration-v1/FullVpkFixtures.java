package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Generates actual signed archives from production A/B Android fixture outputs, never production credentials. */
public final class FullVpkFixtures {
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]), out = Paths.get(args[1]); Files.createDirectories(out);
        MetadataTestSupport keys = new MetadataTestSupport();
        Path first = source.resolve("paravoidAndroidADebug");
        Map<String,Object> ledger = (Map<String,Object>)StrictJson.parse(Files.readAllBytes(first.resolve("baseline-candidate/resource-ledger.json")), 16 * 1024 * 1024);
        String app = (String)ledger.get("applicationId");
        Map<String,String> reservations = new TreeMap<>();
        for (Object value : (List<?>)ledger.get("entries")) { Map<String,Object> e = (Map<String,Object>)value; reservations.put((String)e.get("name"), (String)e.get("id")); }
        Map<String,Object> trust = new LinkedHashMap<>(); trust.put("version", 1); trust.put("applicationId", app);
        trust.put("releaseKeys", MetadataTestSupport.encoded(keys.trust.releaseKeys)); trust.put("headKeys", MetadataTestSupport.encoded(keys.trust.headKeys));
        trust.put("grantKeys", Collections.emptyMap()); trust.put("minimumPayloadVersion", 1); trust.put("minimumHeadRevision", 1);
        byte[] trustBytes = StrictJson.canonical(trust); Files.write(out.resolve("trust.json"), trustBytes);
        Map<String,Object> descriptor = new LinkedHashMap<>(); descriptor.put("profile", "full-vpk-integration-fixture");
        descriptor.put("installed", StrictJson.parse(Files.readAllBytes(first.resolve("baseline-candidate/shell-contract.json")), 1024 * 1024));
        descriptor.put("trust", trust); descriptor.put("baseUrl", args[2]);
        byte[] descriptorBytes = StrictJson.canonical(descriptor); String contract = Digests.sha256(descriptorBytes);
        Files.write(out.resolve("descriptor.json"), descriptorBytes);
        long issued = System.currentTimeMillis() / 1000;
        Map<String,Object> config = new LinkedHashMap<>(); config.put("applicationId", app); config.put("shellContractId", contract);
        config.put("baseUrl", args[2]); config.put("resourceReservations", reservations); config.put("issuedAt", issued);
        Files.write(out.resolve("fixture.json"), StrictJson.canonical(config));
        ShellPolicy policy = new ShellPolicy(app, contract, new SignedMetadataVerifier().readTrustPolicy(trustBytes), args[2], "stable",
            Authentication.PUBLIC, Bootstrap.EMBEDDED, true, true, 1, reservations, descriptorBytes);
        RequestScope device = new RequestScope(app, contract, "stable", 30, Collections.singletonList("x86_64"), 1);
        for (int version = 1; version <= 2; version++) {
            String tag = version == 1 ? "A" : "B";
            Path input = source.resolve("paravoidAndroid" + tag + "Debug"), extracted = out.resolve("components-" + tag); Files.createDirectories(extracted);
            Map<String,File> components = new TreeMap<>();
            try (ZipFile code = new ZipFile(input.resolve("module.zip").toFile())) {
                Enumeration<? extends ZipEntry> entries = code.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    Path target = extracted.resolve(entry.getName());
                    try (InputStream stream = code.getInputStream(entry)) { Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING); }
                    components.put("code/" + entry.getName(), target.toFile());
                }
            }
            components.put("resources.apk", input.resolve("resources/payload-resources.apk").toFile());
            components.put("java-resources.jar", input.resolve("java-resources.jar").toFile());
            components.put("resource-ledger.json", input.resolve("baseline-candidate/resource-ledger.json").toFile());
            VerifiedRelease release = new VpkWriter().write(out.resolve(tag + ".vpk").toFile(), components,
                new VpkWriter.ReleaseSpec("release-" + tag, version, 30, 0, Collections.emptyList()), policy, device, "release", keys.release.getPrivate());
            Map<String,Object> head = keys.headBody(version); head.put("applicationId", app); head.put("shellContractId", contract);
            head.put("issuedAt", issued); head.put("expiresAt", issued + 3600);
            Map<String,Object> offer = new LinkedHashMap<>(); offer.put("releaseId", release.identity.releaseId); offer.put("payloadVersion", release.identity.payloadVersion);
            offer.put("archiveSize", release.identity.archiveSize); offer.put("archiveSha256", release.identity.archiveSha256); offer.put("manifestSha256", release.identity.manifestSha256);
            head.put("release", offer); Files.write(out.resolve("head-" + tag + ".json"), keys.head(head));
            Files.write(out.resolve("release-" + tag + ".json"), release.envelope());
            Map<String,Object> query = new LinkedHashMap<>(); query.put("contract", contract); query.put("channel", "stable"); query.put("sdk", "30");
            query.put("abis", "x86_64"); query.put("runtime", "1"); query.put("format", "1"); query.put("protocol", "1");
            Map<String,Object> entry = new LinkedHashMap<>(); entry.put("applicationId", app); entry.put("query", query); entry.put("file", "head-" + tag + ".json");
            Map<String,Object> artifact = new LinkedHashMap<>(); artifact.put("applicationId", app); artifact.put("releaseId", release.identity.releaseId); artifact.put("file", tag + ".vpk");
            Map<String,Object> catalog = new LinkedHashMap<>(); catalog.put("authentication", "public"); catalog.put("prefix", "/");
            catalog.put("heads", Collections.singletonList(entry)); catalog.put("archives", Collections.singletonList(artifact)); catalog.put("grants", Collections.emptyList());
            Files.write(out.resolve("catalog-" + tag + ".json"), StrictJson.canonical(catalog));
            System.out.println("Verified actual Android VPK " + tag + ": " + release.identity.archiveSize + " bytes");
        }
    }
}
