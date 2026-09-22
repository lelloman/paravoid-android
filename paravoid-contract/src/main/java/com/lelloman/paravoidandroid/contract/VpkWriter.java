package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Build-side producer. Private keys never enter archives; output is verified before publication. */
public final class VpkWriter {
    public static final class ReleaseSpec {
        public final String releaseId;
        public final long payloadVersion;
        public final int minSdk, maxSdk;
        public final List<String> abis;
        public ReleaseSpec(String releaseId, long payloadVersion, int minSdk, int maxSdk, List<String> abis) {
            this.releaseId = releaseId; this.payloadVersion = payloadVersion; this.minSdk = minSdk; this.maxSdk = maxSdk;
            this.abis = Protocol.list(abis);
        }
    }
    public VerifiedRelease write(File output, Map<String,File> components, ReleaseSpec spec,
            ShellPolicy policy, RequestScope device, String keyId, PrivateKey key) throws ContractException {
        if (components.size() > 4095 || components.containsKey("release.json"))
            throw new ContractException(ContractException.Code.MALFORMED, "Invalid producer inventory");
        Path temporary = null;
        try {
            TreeMap<String,File> sorted = new TreeMap<>(components);
            Map<String,Stats> stats = new LinkedHashMap<>(); List<Object> inventory = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String,File> source : sorted.entrySet()) {
                Stats item = measure(source.getValue()); total += item.size;
                if (total > Protocol.MAX_ARCHIVE_BYTES) throw new ContractException(ContractException.Code.LIMIT_EXCEEDED, "Producer content limit");
                stats.put(source.getKey(), item);
                Map<String,Object> row = new LinkedHashMap<>(); row.put("path", source.getKey()); row.put("size", item.size); row.put("sha256", item.sha);
                inventory.add(row);
            }
            Stats ledger = stats.get("resource-ledger.json");
            if (ledger == null) throw new ContractException(ContractException.Code.MALFORMED, "Missing resource ledger");
            Map<String,Object> release = new LinkedHashMap<>();
            release.put("version", 1); release.put("applicationId", policy.applicationId); release.put("shellContractId", policy.shellContractId);
            release.put("releaseId", spec.releaseId); release.put("payloadVersion", spec.payloadVersion); release.put("runtimeAbi", policy.runtimeAbi);
            release.put("formatVersion", 1); release.put("minSdk", spec.minSdk); release.put("maxSdk", spec.maxSdk); release.put("abis", spec.abis);
            release.put("ledgerSha256", ledger.sha); release.put("inventory", inventory);
            byte[] body = StrictJson.canonical(release);
            Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key);
            signature.update("paravoid/v1/release\n".getBytes(StandardCharsets.US_ASCII)); signature.update(body);
            Map<String,Object> envelope = new LinkedHashMap<>(); envelope.put("keyId", keyId);
            envelope.put("body", Base64.getEncoder().encodeToString(body)); envelope.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
            byte[] manifest = StrictJson.canonical(envelope);
            if (manifest.length > Protocol.MAX_RELEASE_BYTES) throw new ContractException(ContractException.Code.LIMIT_EXCEEDED, "Release envelope limit");
            Path target = output.toPath().toAbsolutePath(); Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".paravoid-vpk-", ".tmp");
            TreeSet<String> names = new TreeSet<>(sorted.keySet()); names.add("release.json");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Calendar fixed = Calendar.getInstance(); fixed.clear(); fixed.set(1980, Calendar.JANUARY, 1, 0, 0, 0);
                for (String name : names) {
                    Stats item = name.equals("release.json") ? measure(manifest) : stats.get(name);
                    ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(item.size);
                    entry.setCompressedSize(item.size); entry.setCrc(item.crc); entry.setTime(fixed.getTimeInMillis()); zip.putNextEntry(entry);
                    if (name.equals("release.json")) zip.write(manifest);
                    else try (InputStream input = new FileInputStream(sorted.get(name))) {
                        byte[] buffer = new byte[8192]; int n; long copied = 0;
                        while ((n = input.read(buffer)) != -1) { copied += n; if (copied > item.size) throw new IOException("Producer input changed"); zip.write(buffer, 0, n); }
                    }
                    zip.closeEntry();
                }
            }
            VerifiedRelease verified = new CompleteVpkVerifier().verifyEmbedded(temporary.toFile(), policy, device);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); temporary = null;
            return verified;
        } catch (IOException error) { throw new ContractException(ContractException.Code.IO, "VPK production failed"); }
        catch (GeneralSecurityException error) { throw new ContractException(ContractException.Code.INVALID_SIGNATURE, "VPK signing failed"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Never publish failed output. */ } }
    }
    private static final class Stats {
        final long size, crc; final String sha;
        Stats(long size, long crc, String sha) { this.size = size; this.crc = crc; this.sha = sha; }
    }
    private static Stats measure(File file) throws IOException, ContractException {
        try (InputStream input = new FileInputStream(file)) { return measure(input); }
    }
    private static Stats measure(byte[] bytes) throws IOException, ContractException { return measure(new ByteArrayInputStream(bytes)); }
    private static Stats measure(InputStream input) throws IOException, ContractException {
        MessageDigest hash = CheckedZip.sha(); CRC32 crc = new CRC32(); long size = 0;
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) {
            size += n; if (size > Protocol.MAX_ARCHIVE_BYTES) throw new ContractException(ContractException.Code.LIMIT_EXCEEDED, "Producer input limit");
            hash.update(buffer, 0, n); crc.update(buffer, 0, n);
        }
        return new Stats(size, crc.getValue(), CheckedZip.hex(hash.digest()));
    }
}
