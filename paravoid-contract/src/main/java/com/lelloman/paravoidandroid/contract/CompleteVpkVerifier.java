package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.Adler32;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static com.lelloman.paravoidandroid.contract.SignedMetadataVerifier.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Complete container authentication/compatibility gate; no writes, extraction or execution. */
public final class CompleteVpkVerifier implements VpkVerifier {
    private static final long MAX_EXPANDED = 2L * 1024 * 1024 * 1024;
    private static final int MAX_LEDGER = 16 * 1024 * 1024;
    @Override public VerifiedRelease verifyDownloaded(File archive, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
        return verify(archive, policy, device, Objects.requireNonNull(expected));
    }
    @Override public VerifiedRelease verifyRetained(File archive, ShellPolicy policy, RequestScope device, ExpectedArchive accepted) throws ContractException {
        return verify(archive, policy, device, Objects.requireNonNull(accepted));
    }
    @Override public VerifiedRelease verifyEmbedded(File archive, ShellPolicy policy, RequestScope device) throws ContractException {
        return verify(archive, policy, device, null);
    }
    private VerifiedRelease verify(File archive, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
        validatePolicy(policy); scope(policy, device);
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long size = file.length();
            if (size < 22 || size > Protocol.MAX_ARCHIVE_BYTES) throw fail(LIMIT_EXCEEDED, "VPK byte limit");
            if (expected != null && size != expected.archiveSize) throw fail(INTEGRITY, "VPK size mismatch");
            String archiveHash = fileHash(file, size);
            if (expected != null && !archiveHash.equals(expected.archiveSha256)) throw fail(INTEGRITY, "VPK digest mismatch");
            CheckedZip zip = new CheckedZip(file, 0, size, true, 4096);
            byte[] manifest = zip.read(zip.entries.get("release.json"), Protocol.MAX_RELEASE_BYTES);
            String manifestHash = Digests.sha256(manifest);
            if (expected != null && !manifestHash.equals(expected.manifestSha256)) throw fail(INTEGRITY, "Release envelope digest mismatch");
            Envelope envelope = authenticate(manifest, "release", policy.trust.releaseKeys, Protocol.MAX_RELEASE_BYTES);
            Map<String,Object> b = envelope.body;
            fields(b, "version applicationId shellContractId releaseId payloadVersion runtimeAbi formatVersion minSdk maxSdk abis ledgerSha256 inventory");
            version(b, "version"); version(b, "formatVersion");
            if (!policy.applicationId.equals(string(b, "applicationId")) || !policy.shellContractId.equals(hash(b, "shellContractId"))
                    || policy.runtimeAbi != number(b, "runtimeAbi", 1)) throw fail(INCOMPATIBLE, "VPK installed scope mismatch");
            long minimum = number(b, "minSdk", 1), maximum = number(b, "maxSdk", 0);
            if (minimum < 30 || minimum > Integer.MAX_VALUE || maximum > Integer.MAX_VALUE || (maximum != 0 && maximum < minimum)
                    || device.sdk < minimum || (maximum != 0 && device.sdk > maximum)) throw fail(INCOMPATIBLE, "VPK SDK range");
            List<String> abis = abis(b.get("abis"));
            if (!abis.isEmpty() && Collections.disjoint(abis, device.abis)) throw fail(INCOMPATIBLE, "No process-compatible native ABI");
            ExpectedArchive identity = new ExpectedArchive(identifier(b, "releaseId"), number(b, "payloadVersion", 1), manifestHash, archiveHash, size);
            if (identity.payloadVersion < policy.trust.minimumPayloadVersion) throw fail(REPLAY, "VPK below installed version floor");
            if (expected != null && !expected.equals(identity)) throw fail(IDENTITY_CONFLICT, "VPK offered identity mismatch");
            if (!(b.get("inventory") instanceof List)) throw fail(MALFORMED, "Invalid inventory");
            List<?> rows = (List<?>)b.get("inventory");
            if (rows.size() < 4 || rows.size() > 4095) throw fail(LIMIT_EXCEEDED, "Inventory count");
            List<InventoryEntry> inventory = new ArrayList<>();
            Set<String> names = new HashSet<>(), nativeAbis = new HashSet<>();
            TreeSet<Integer> dexNumbers = new TreeSet<>();
            String previous = "";
            long expanded = 0; int nestedEntries = 0;
            for (Object row : rows) {
                Map<String,Object> r = object(row); fields(r, "path size sha256");
                String path = string(r, "path"), digest = hash(r, "sha256");
                long componentSize = number(r, "size", 0);
                if (path.compareTo(previous) <= 0 || !names.add(path)) throw fail(MALFORMED, "Inventory order/duplicates");
                previous = path;
                CheckedZip.Entry entry = zip.entries.get(path);
                if (entry == null || entry.size != componentSize) throw fail(INTEGRITY, "Inventory coverage/size mismatch");
                InventoryEntry component = new InventoryEntry(path, componentSize, digest); inventory.add(component);
                if (path.matches("code/classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
                    int number = path.equals("code/classes.dex") ? 1 : dexNumber(path);
                    dexNumbers.add(number);
                    DexCheck dex = new DexCheck();
                    zip.check(entry, 128L * 1024 * 1024, digest, dex); dex.finish(componentSize);
                    expanded += componentSize;
                } else if (path.equals("resources.apk") || path.equals("java-resources.jar")) {
                    zip.check(entry, Protocol.MAX_ARCHIVE_BYTES, digest, null);
                    CheckedZip nested = new CheckedZip(file, entry.data, entry.size, false, 100000 - nestedEntries);
                    nestedEntries += nested.entries.size();
                    for (CheckedZip.Entry child : nested.entries.values()) {
                        expanded += child.size;
                        if (expanded > MAX_EXPANDED) throw fail(LIMIT_EXCEEDED, "Expanded component limit");
                        if (path.equals("java-resources.jar") && (child.name.endsWith(".class") || child.name.endsWith(".dex")))
                            throw fail(MALFORMED, "Executable Java-resource entry");
                        nested.check(child, MAX_EXPANDED, null, null);
                    }
                    if (path.equals("resources.apk")) {
                        CheckedZip.Entry table = nested.entries.get("resources.arsc");
                        byte[] header = nested.prefix(table, 12);
                        if (u32(header, 0) != 0x000c0002L || u32(header, 4) != table.size)
                            throw fail(MALFORMED, "Invalid resource table header");
                    }
                } else if (path.equals("resource-ledger.json")) {
                    zip.check(entry, MAX_LEDGER, digest, null); expanded += componentSize;
                    if (!digest.equals(hash(b, "ledgerSha256"))) throw fail(INTEGRITY, "Resource ledger digest mismatch");
                    ledger(zip.read(entry, MAX_LEDGER), policy);
                } else if (path.matches("native/(arm64-v8a|armeabi-v7a|x86|x86_64)/[A-Za-z0-9_+.-]+\\.so")) {
                    String abi = path.split("/")[1]; nativeAbis.add(abi);
                    zip.check(entry, 256L * 1024 * 1024, digest, null); expanded += componentSize;
                    byte[] header = zip.prefix(entry, 20);
                    int clazz = abi.equals("arm64-v8a") || abi.equals("x86_64") ? 2 : 1;
                    int machine = abi.equals("arm64-v8a") ? 183 : abi.equals("armeabi-v7a") ? 40 : abi.equals("x86_64") ? 62 : 3;
                    if (u32(header, 0) != 0x464c457fL || header[4] != clazz || header[5] != 1 || header[6] != 1
                            || header[16] != 3 || header[17] != 0 || (header[18] & 255) != machine || header[19] != 0)
                        throw fail(INCOMPATIBLE, "Native ELF ABI mismatch");
                } else throw fail(MALFORMED, "Unknown component role");
                if (expanded > MAX_EXPANDED) throw fail(LIMIT_EXCEEDED, "Expanded component limit");
            }
            if (!names.containsAll(Arrays.asList("code/classes.dex", "resources.apk", "java-resources.jar", "resource-ledger.json"))
                    || zip.entries.size() != names.size() + 1 || !nativeAbis.equals(new HashSet<>(abis))) throw fail(MALFORMED, "Incomplete component inventory");
            int sequence = 1; for (int n : dexNumbers) if (n != sequence++) throw fail(MALFORMED, "Noncontiguous DEX sequence");
            if (file.length() != size || !archiveHash.equals(fileHash(file, size))) throw fail(INTEGRITY, "VPK changed during verification");
            return new VerifiedRelease(policy.applicationId, policy.shellContractId, envelope.keyId, hash(b, "ledgerSha256"),
                identity, policy.runtimeAbi, (int)minimum, (int)maximum, abis, inventory, manifest);
        } catch (IOException error) { throw fail(IO, "Cannot read complete VPK"); }
    }
    private static int dexNumber(String path) throws ContractException {
        try { int n = Integer.parseInt(path.substring(12, path.length() - 4)); if (n > 4096) throw fail(LIMIT_EXCEEDED, "DEX count"); return n; }
        catch (NumberFormatException error) { throw fail(MALFORMED, "DEX sequence number"); }
    }
    private static void ledger(byte[] bytes, ShellPolicy policy) throws ContractException {
        Map<String,Object> root = object(StrictJson.parse(bytes, MAX_LEDGER));
        fields(root, "version applicationId entries"); version(root, "version");
        if (!policy.applicationId.equals(string(root, "applicationId")) || !(root.get("entries") instanceof List)) throw fail(INCOMPATIBLE, "Ledger application mismatch");
        List<?> entries = (List<?>)root.get("entries");
        if (entries.size() > 100000) throw fail(LIMIT_EXCEEDED, "Ledger entry count");
        Map<String,String> names = new HashMap<>(), ids = new HashMap<>(), types = new HashMap<>(), typeIds = new HashMap<>();
        for (Object item : entries) {
            Map<String,Object> entry = object(item); fields(entry, "name id removed");
            String name = string(entry, "name"), id = string(entry, "id");
            if (name.length() > 4096 || !name.matches("[a-z][a-z0-9_]*/[A-Za-z_][A-Za-z0-9_.]*")
                    || !id.matches("0x7f[0-9a-f]{6}") || !(entry.get("removed") instanceof Boolean)
                    || names.put(name, id) != null || ids.put(id, name) != null) throw fail(MALFORMED, "Invalid ledger entry");
            String type = name.substring(0, name.indexOf('/')), typeId = id.substring(4, 6);
            String oldType = types.put(type, typeId), oldId = typeIds.put(typeId, type);
            if (typeId.equals("00") || (oldType != null && !oldType.equals(typeId)) || (oldId != null && !oldId.equals(type)))
                throw fail(MALFORMED, "Ledger type ID reuse");
        }
        for (Map.Entry<String,String> reserved : policy.resourceReservations.entrySet())
            if (!reserved.getValue().equals(names.get(reserved.getKey()))) throw fail(INCOMPATIBLE, "Installed resource reservation changed");
    }
    static String fileHash(RandomAccessFile file, long length) throws IOException {
        MessageDigest digest = CheckedZip.sha(); file.seek(0); byte[] buffer = new byte[8192]; long remaining = length;
        while (remaining > 0) { int n = (int)Math.min(buffer.length, remaining); file.readFully(buffer, 0, n); digest.update(buffer, 0, n); remaining -= n; }
        return CheckedZip.hex(digest.digest());
    }
    static long u32(byte[] b, int at) { return (b[at] & 255L) | ((b[at + 1] & 255L) << 8) | ((b[at + 2] & 255L) << 16) | ((b[at + 3] & 255L) << 24); }
    private static final class DexCheck extends OutputStream {
        final byte[] header = new byte[112];
        final Adler32 adler = new Adler32();
        final MessageDigest signature;
        long position;
        DexCheck() { try { signature = MessageDigest.getInstance("SHA-1"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
        @Override public void write(int b) { write(new byte[]{(byte)b}, 0, 1); }
        @Override public void write(byte[] b, int at, int count) {
            if (position < header.length) System.arraycopy(b, at, header, (int)position, (int)Math.min(count, header.length - position));
            int skip = (int)Math.max(0, Math.min(count, 12 - position)); adler.update(b, at + skip, count - skip);
            skip = (int)Math.max(0, Math.min(count, 32 - position)); signature.update(b, at + skip, count - skip); position += count;
        }
        void finish(long length) throws ContractException {
            String magic = new String(header, 0, 8, java.nio.charset.StandardCharsets.US_ASCII);
            if (length < 112 || !(magic.equals("dex\n035\0") || magic.equals("dex\n037\0") || magic.equals("dex\n038\0") || magic.equals("dex\n039\0"))
                    || u32(header, 32) != length || u32(header, 36) != 112 || u32(header, 40) != 0x12345678L
                    || u32(header, 8) != adler.getValue() || !Arrays.equals(Arrays.copyOfRange(header, 12, 32), signature.digest()))
                throw fail(MALFORMED, "Invalid or unsupported DEX header/checksum");
        }
    }
}
