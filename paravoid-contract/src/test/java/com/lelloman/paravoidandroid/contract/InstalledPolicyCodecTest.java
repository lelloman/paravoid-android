package com.lelloman.paravoidandroid.contract;

import java.util.*;
import org.junit.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static org.junit.Assert.*;

public class InstalledPolicyCodecTest {
    static MetadataTestSupport f;
    @BeforeClass public static void keys() throws Exception { f = new MetadataTestSupport(); }
    static byte[] boundary() throws Exception {
        Map<String,Object> d = new LinkedHashMap<>();
        d.put("profile", "embedded-apk-v1"); d.put("applicationId", f.policy.applicationId); d.put("minSdk", 30);
        d.put("manifestSha256", "b".repeat(64)); d.put("declarations", Collections.singletonMap("application", "example.App"));
        d.put("pinnedResources", Collections.emptyMap()); d.put("runtimeClasses", Collections.singletonMap("runtime.class", "c".repeat(64)));
        d.put("nativeAbis", Collections.emptyMap()); d.put("ledgerReservations", Collections.singletonMap("string/title", "0x7f010000"));
        d.put("apkSigners", Collections.singletonList("d".repeat(64))); d.put("toolchain", Collections.singletonMap("agp", "8.13.2"));
        Map<String,Object> distribution = new LinkedHashMap<>(); distribution.put("bootstrap", "embedded"); distribution.put("updates", false); d.put("distribution", distribution);
        Map<String,Object> wrapper = new LinkedHashMap<>(); wrapper.put("version", 1); wrapper.put("descriptor", d); wrapper.put("contractId", Digests.sha256(StrictJson.canonical(d)));
        return StrictJson.canonical(wrapper);
    }
    byte[] create(String url, Bootstrap bootstrap, boolean enabled, boolean debug, boolean debuggable) throws Exception {
        return InstalledPolicyCodec.create(boundary(), f.trustBytes(), bootstrap, enabled, url, "stable", Authentication.APK_KEY, debug, debuggable);
    }
    @Test public void roundTripsAndRetainsInstalledReservations() throws Exception {
        byte[] policy = create(f.policy.baseUrl, Bootstrap.EMBEDDED, true, false, false);
        ShellPolicy read = InstalledPolicyCodec.read(policy, false);
        assertEquals(f.policy.applicationId, read.applicationId);
        assertEquals("0x7f010000", read.resourceReservations.get("string/title"));
        assertEquals(Authentication.APK_KEY, read.authentication);
        assertEquals(Digests.sha256(read.contractDescriptor()), read.shellContractId);
        byte[] recovered = InstalledPolicyCodec.installedBoundary(policy, false);
        assertEquals(new String(boundary(), java.nio.charset.StandardCharsets.UTF_8), new String(recovered, java.nio.charset.StandardCharsets.UTF_8).trim());
    }
    @Test public void distributionChangesAreNewContractsAndNoSecretsAreFields() throws Exception {
        String a = InstalledPolicyCodec.read(create(f.policy.baseUrl, Bootstrap.EMBEDDED, true, false, false), false).shellContractId;
        String b = InstalledPolicyCodec.read(create("https://other.example/", Bootstrap.EMBEDDED, true, false, false), false).shellContractId;
        String c = InstalledPolicyCodec.read(create(f.policy.baseUrl, Bootstrap.EMPTY, true, false, false), false).shellContractId;
        assertNotEquals(a, b); assertNotEquals(a, c);
    }
    @Test public void releaseRejectsDebugHttpAndEmptyOffline() throws Exception {
        assertThrows(ContractException.class, () -> create("http://127.0.0.1/", Bootstrap.EMBEDDED, true, true, false));
        byte[] debug = create("http://127.0.0.1/", Bootstrap.EMBEDDED, true, true, true);
        assertTrue(InstalledPolicyCodec.read(debug, true).debugHttpAllowed);
        assertThrows(ContractException.class, () -> InstalledPolicyCodec.read(debug, false));
        assertThrows(ContractException.class, () -> create("", Bootstrap.EMPTY, false, false, false));
    }
    @Test public void rejectsTamperingDuplicatesAndUnknownFields() throws Exception {
        String text = new String(create(f.policy.baseUrl, Bootstrap.EMBEDDED, true, false, false), java.nio.charset.StandardCharsets.UTF_8);
        for (String bad : Arrays.asList(text.replace("example.App", "example.Other"),
                text.replace("\"version\":1", "\"version\":1,\"version\":1"), text.replaceFirst("\\{", "{\"credential\":\"forbidden\",")))
            assertThrows(ContractException.class, () -> InstalledPolicyCodec.read(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8), false));
    }
}
