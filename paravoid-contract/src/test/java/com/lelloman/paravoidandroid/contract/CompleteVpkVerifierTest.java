package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static org.junit.Assert.*;

public class CompleteVpkVerifierTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static MetadataTestSupport f;
    final CompleteVpkVerifier verifier = new CompleteVpkVerifier();
    @BeforeClass public static void keys() throws Exception { f = new MetadataTestSupport(); }
    @Test public void writerIsDeterministicAndAllThreeVerificationPathsAgree() throws Exception {
        Map<String,File> inputs = files(components("A"));
        File one = temp.newFile(), two = temp.newFile();
        VpkWriter writer = new VpkWriter();
        VpkWriter.ReleaseSpec spec = new VpkWriter.ReleaseSpec("a", 1, 30, 0, Collections.emptyList());
        VerifiedRelease release = writer.write(one, inputs, spec, f.policy, f.scope, "release", f.release.getPrivate());
        writer.write(two, inputs, spec, f.policy, f.scope, "release", f.release.getPrivate());
        assertArrayEquals(Files.readAllBytes(one.toPath()), Files.readAllBytes(two.toPath()));
        assertEquals(4, release.inventory.size());
        assertEquals(release.identity, verifier.verifyDownloaded(one, f.policy, f.scope, release.identity).identity);
        assertEquals(release.identity, verifier.verifyRetained(one, f.policy, f.scope, release.identity).identity);
        File b = pack(components("B"), Collections.singletonMap("payloadVersion", 2));
        VerifiedRelease next = verifier.verifyEmbedded(b, f.policy, f.scope);
        assertEquals(release.shellContractId, next.shellContractId);
        assertNotEquals(release.identity.archiveSha256, next.identity.archiveSha256);
    }
    @Test public void signedInventoryStillRejectsUnknownMissingAndNoncontiguousRoles() throws Exception {
        Map<String,byte[]> c = components("A"); c.put("extra", new byte[]{1}); rejected(pack(c, Collections.emptyMap()));
        c = components("A"); c.remove("java-resources.jar"); rejected(pack(c, Collections.emptyMap()));
        c = components("A"); c.put("code/classes3.dex", dex()); rejected(pack(c, Collections.emptyMap()));
        c = components("A"); c.put("code/classes2.dex", dex()); assertEquals(5, verifier.verifyEmbedded(pack(c, Collections.emptyMap()), f.policy, f.scope).inventory.size());
    }
    @Test public void rejectsIdentityScopeRuntimeSdkAndInstalledFloors() throws Exception {
        for (Map<String,Object> change : Arrays.asList(Collections.<String,Object>singletonMap("applicationId", "wrong.app"),
                Collections.<String,Object>singletonMap("shellContractId", "f".repeat(64)),
                Collections.<String,Object>singletonMap("runtimeAbi", 2), Collections.<String,Object>singletonMap("minSdk", 31),
                Collections.<String,Object>singletonMap("formatVersion", 2))) rejected(pack(components("A"), change));
        File file = pack(components("A"), Collections.emptyMap());
        VerifiedRelease release = verifier.verifyEmbedded(file, f.policy, f.scope);
        ExpectedArchive wrong = new ExpectedArchive("wrong", release.identity.payloadVersion, release.identity.manifestSha256,
            release.identity.archiveSha256, release.identity.archiveSize);
        assertThrows(ContractException.class, () -> verifier.verifyDownloaded(file, f.policy, f.scope, wrong));
    }
    @Test public void rejectsOuterZipTamperingAndStructuralAmbiguity() throws Exception {
        File valid = pack(components("A"), Collections.emptyMap()); byte[] original = Files.readAllBytes(valid.toPath());
        byte[] altered = original.clone(); altered[14] ^= 1; rejected(write(altered)); // CRC disagreement
        altered = Arrays.copyOf(original, original.length + 1); rejected(write(altered)); // trailing bytes
        altered = new byte[original.length + 1]; System.arraycopy(original, 0, altered, 1, original.length); rejected(write(altered));
        int central = signature(original, 0x02014b50); altered = original.clone(); altered[central + 38 + 3] = (byte)0xa0;
        rejected(write(altered)); // Unix symlink
        altered = original.clone(); altered[6] |= 1; rejected(write(altered)); // encryption/local-central mismatch
    }
    @Test public void rejectsSignedMalformedNestedContentAndExecutablesInJavaResources() throws Exception {
        Map<String,byte[]> c = components("A"); c.put("java-resources.jar", zip(Collections.singletonMap("Injected.class", new byte[]{1}), false));
        rejected(pack(c, Collections.emptyMap()));
        c = components("A"); c.put("java-resources.jar", zip(Collections.singletonMap("../escape", new byte[]{1}), false));
        rejected(pack(c, Collections.emptyMap()));
        c = components("A"); c.put("resources.apk", zip(Collections.singletonMap("resources.arsc", new byte[12]), false));
        rejected(pack(c, Collections.emptyMap()));
        c = components("A"); byte[] badDex = dex(); badDex[8] ^= 1; c.put("code/classes.dex", badDex);
        rejected(pack(c, Collections.emptyMap()));
        c = components("A"); byte[] nested = zip(new LinkedHashMap<String,byte[]>() {{ put("aaa", new byte[]{1}); put("bbb", new byte[]{2}); }}, false);
        for (int i = 0; i < nested.length - 2; i++) if (nested[i] == 'b' && nested[i + 1] == 'b' && nested[i + 2] == 'b') nested[i] = nested[i + 1] = nested[i + 2] = 'a';
        c.put("java-resources.jar", nested); rejected(pack(c, Collections.emptyMap()));
    }
    @Test public void verifiesNativeAbiAndLedgerReservations() throws Exception {
        Map<String,byte[]> c = components("A"); byte[] elf = new byte[64];
        elf[0] = 127; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F'; elf[4] = 2; elf[5] = 1; elf[6] = 1; elf[16] = 3; elf[18] = 62;
        c.put("native/x86_64/libc++_shared.so", elf);
        assertEquals(Collections.singletonList("x86_64"), verifier.verifyEmbedded(pack(c, Collections.singletonMap("abis", Collections.singletonList("x86_64"))), f.policy, f.scope).abis);
        rejected(pack(c, Collections.emptyMap()));
        elf[18] = 3; rejected(pack(c, Collections.singletonMap("abis", Collections.singletonList("x86_64"))));
        ShellPolicy reserved = new ShellPolicy(f.policy.applicationId, f.policy.shellContractId, f.trust,
            f.policy.baseUrl, f.policy.channel, f.policy.authentication, f.policy.bootstrap, true, false, 1,
            Collections.singletonMap("string/title", "0x7f010000"), new byte[0]);
        File missing = pack(components("A"), Collections.emptyMap());
        assertThrows(ContractException.class, () -> verifier.verifyEmbedded(missing, reserved, f.scope));
    }
    @Test public void boundsActualNestedInflationRatherThanTrustingDeclaredSize() throws Exception {
        Map<String,byte[]> c = components("A");
        byte[] nested = zip(Collections.singletonMap("bomb", new byte[1024 * 1024]), false);
        int central = signature(nested, 0x02014b50);
        ByteBuffer.wrap(nested).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 1).putInt(central - 4, 1);
        c.put("java-resources.jar", nested);
        rejected(pack(c, Collections.emptyMap()));
    }
    @Test public void failedProducerNeverReplacesExistingOutput() throws Exception {
        File output = write(new byte[]{42});
        Map<String,byte[]> invalid = components("A"); invalid.put("unexpected", new byte[0]);
        assertThrows(ContractException.class, () -> new VpkWriter().write(output, files(invalid),
            new VpkWriter.ReleaseSpec("a", 1, 30, 0, Collections.emptyList()), f.policy, f.scope, "release", f.release.getPrivate()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void acceptsOnlyZeroLocalZipalignPaddingInNestedContainers() throws Exception {
        byte[] original = zip(Collections.singletonMap("probe.txt", new byte[]{42}), true);
        int nameLength = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getShort(26) & 65535;
        int extraLength = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getShort(28) & 65535;
        int insertion = 30 + nameLength + extraLength;
        int central = signature(original, 0x02014b50), end = signature(original, 0x06054b50);
        for (int padding = 1; padding <= 3; padding++) {
            byte[] aligned = new byte[original.length + padding];
            System.arraycopy(original, 0, aligned, 0, insertion);
            System.arraycopy(original, insertion, aligned, insertion + padding, original.length - insertion);
            ByteBuffer.wrap(aligned).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short)(extraLength + padding))
                .putInt(end + padding + 16, central + padding);
            Map<String,byte[]> c = components("A"); c.put("java-resources.jar", aligned);
            verifier.verifyEmbedded(pack(c, Collections.emptyMap()), f.policy, f.scope);
            aligned[insertion] = 1;
            rejected(pack(c, Collections.emptyMap()));
        }
    }
    private void rejected(File file) { assertThrows(ContractException.class, () -> verifier.verifyEmbedded(file, f.policy, f.scope)); }
    File pack(Map<String,byte[]> components, Map<String,Object> changes) throws Exception {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("version", 1); body.put("applicationId", f.policy.applicationId); body.put("shellContractId", f.policy.shellContractId);
        body.put("releaseId", "a"); body.put("payloadVersion", 1); body.put("runtimeAbi", 1); body.put("formatVersion", 1);
        body.put("minSdk", 30); body.put("maxSdk", 0); body.put("abis", Collections.emptyList());
        body.put("ledgerSha256", Digests.sha256(components.get("resource-ledger.json")));
        List<Object> inventory = new ArrayList<>();
        for (Map.Entry<String,byte[]> e : new TreeMap<>(components).entrySet()) {
            Map<String,Object> r = new LinkedHashMap<>(); r.put("path", e.getKey()); r.put("size", e.getValue().length); r.put("sha256", Digests.sha256(e.getValue())); inventory.add(r);
        }
        body.put("inventory", inventory); body.putAll(changes);
        Map<String,byte[]> entries = new TreeMap<>(components);
        entries.put("release.json", MetadataTestSupport.signed("release", "release", f.release.getPrivate(), StrictJson.canonical(body)));
        return write(zip(entries, true));
    }
    Map<String,byte[]> components(String content) throws Exception {
        Map<String,byte[]> c = new LinkedHashMap<>(); c.put("code/classes.dex", dex());
        byte[] table = new byte[12]; ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).putInt(0x000c0002).putInt(12).putInt(0);
        Map<String,byte[]> resources = new LinkedHashMap<>(); resources.put("resources.arsc", table); resources.put("assets/value", content.getBytes(StandardCharsets.UTF_8));
        c.put("resources.apk", zip(resources, false)); c.put("java-resources.jar", zip(Collections.singletonMap("probe.txt", content.getBytes(StandardCharsets.UTF_8)), false));
        c.put("resource-ledger.json", ("{\"version\":1,\"applicationId\":\"" + f.policy.applicationId + "\",\"entries\":[]}").getBytes(StandardCharsets.UTF_8)); return c;
    }
    private Map<String,File> files(Map<String,byte[]> data) throws Exception {
        Map<String,File> result = new LinkedHashMap<>(); for (Map.Entry<String,byte[]> entry : data.entrySet()) result.put(entry.getKey(), write(entry.getValue())); return result;
    }
    private File write(byte[] data) throws Exception { File file = temp.newFile(); Files.write(file.toPath(), data); return file; }
    static byte[] zip(Map<String,byte[]> entries, boolean stored) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String,byte[]> item : entries.entrySet()) {
                ZipEntry e = new ZipEntry(item.getKey()); e.setTime(315532800000L);
                if (stored) { CRC32 crc = new CRC32(); crc.update(item.getValue()); e.setMethod(ZipEntry.STORED); e.setSize(item.getValue().length); e.setCrc(crc.getValue()); }
                zip.putNextEntry(e); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    static byte[] dex() throws Exception {
        byte[] dex = new byte[112]; System.arraycopy("dex\n039\0".getBytes(StandardCharsets.US_ASCII), 0, dex, 0, 8);
        ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 112).putInt(36, 112).putInt(40, 0x12345678);
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(dex, 32, dex.length)); System.arraycopy(sha, 0, dex, 12, 20);
        Adler32 adler = new Adler32(); adler.update(dex, 12, dex.length - 12); ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int)adler.getValue()); return dex;
    }
    static int signature(byte[] bytes, int magic) { for (int i = 0; i <= bytes.length - 4; i++) if (CompleteVpkVerifier.u32(bytes, i) == (magic & 0xffffffffL)) return i; throw new AssertionError(); }
}
