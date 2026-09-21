package com.lelloman.paravoidandroid.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import static com.lelloman.paravoidandroid.runtime.SignedDeliveryProbe.require;

/** Strict stored-ZIP fixture reader. No extraction, decompression, or loading. */
public final class ArchiveProbe {
    private static final int ENTRY = 128 * 1024, TOTAL = 512 * 1024;
    private static long u32(byte[] data, int offset) throws Exception {
        require(offset >= 0 && offset <= data.length - 4, "archive-layout");
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
            | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static int u16(byte[] data, int offset) throws Exception {
        require(offset >= 0 && offset <= data.length - 2, "archive-layout");
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }
    private static byte[] slice(byte[] data, int start, int length) throws Exception {
        require(start >= 0 && length >= 0 && start <= data.length - length, "archive-layout");
        return Arrays.copyOfRange(data, start, start + length);
    }
    private static String ascii(byte[] bytes) throws Exception {
        for (byte value : bytes) require(value >= 0, "archive-path");
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static boolean safe(String path) {
        return path.length() <= 96 && path.matches("[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*");
    }
    private static Map<String, byte[]> entries(byte[] data) throws Exception {
        require(data.length >= 22 && data.length <= 1024 * 1024, "archive-bounds");
        int end = data.length - 22;
        require(u32(data, end) == 0x06054b50L && u16(data, end + 4) == 0 && u16(data, end + 6) == 0
            && u16(data, end + 20) == 0, "archive-layout");
        int count = u16(data, end + 10);
        require(count >= 3 && count <= 18 && u16(data, end + 8) == count, "archive-count");
        long directory = u32(data, end + 16), directorySize = u32(data, end + 12);
        require(directory + directorySize == end && directory <= end, "archive-layout");
        int central = (int) directory, local = 0, total = 0;
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            require(central <= end - 46 && u32(data, central) == 0x02014b50L, "archive-layout");
            int nameLength = u16(data, central + 28);
            long size = u32(data, central + 24), packed = u32(data, central + 20);
            require(size > 0 && size <= ENTRY && size == packed, "archive-bounds");
            total += (int) size;
            require(total <= TOTAL + 8192, "archive-bounds");
            require(u16(data, central + 4) == 0x0314 && u16(data, central + 6) == 20
                && u16(data, central + 8) == 0 && u16(data, central + 10) == 0
                && u16(data, central + 30) == 0 && u16(data, central + 32) == 0
                && u16(data, central + 34) == 0 && u16(data, central + 36) == 0
                && u32(data, central + 38) == (0100644L << 16), "archive-feature");
            require(nameLength > 0 && nameLength <= 96 && central + 46 + nameLength <= end, "archive-path");
            byte[] name = slice(data, central + 46, nameLength);
            String path = ascii(name);
            require(safe(path), "archive-path");
            require(!result.containsKey(path), "archive-duplicate");
            require(u32(data, central + 42) == local && local <= directory - 30
                && u32(data, local) == 0x04034b50L, "archive-layout");
            // Matching fields include version, flags, method, timestamps, CRC, sizes and filename.
            require(Arrays.equals(slice(data, local + 4, 22), slice(data, central + 6, 22))
                && u16(data, local + 26) == nameLength && u16(data, local + 28) == 0
                && Arrays.equals(slice(data, local + 30, nameLength), name), "archive-layout");
            int start = local + 30 + nameLength;
            require(start + size <= directory, "archive-layout");
            byte[] bytes = slice(data, start, (int) size);
            CRC32 crc = new CRC32(); crc.update(bytes);
            require(crc.getValue() == u32(data, central + 16), "archive-crc");
            result.put(path, bytes);
            local = start + (int) size;
            central += 46 + nameLength;
        }
        require(local == directory && central == end, "archive-layout");
        return result;
    }
    public static int verify(byte[] bytes, Map<String, String> head, byte[] root) throws Exception {
        return verifiedFiles(bytes, head, root).size() - 2;
    }
    static Map<String, byte[]> verifiedFiles(byte[] bytes, Map<String, String> head, byte[] root) throws Exception {
        Map<String, byte[]> files = entries(bytes);
        require(files.containsKey("manifest.sig") && files.containsKey("inventory.txt"), "archive-metadata");
        byte[] inventory = files.get("inventory.txt");
        require(inventory.length <= 4096 && files.get("manifest.sig").length <= 4096, "archive-bounds");
        Map<String, String> manifest = SignedDeliveryProbe.verify(files.get("manifest.sig"), "manifest", root);
        for (String field : new String[]{"applicationId", "contract", "releaseId", "payloadVersion"})
            require(manifest.get(field).equals(head.get(field)), "manifest-scope");
        require(manifest.get("format").equals("stored-inventory-1"), "manifest-format");
        require(manifest.get("inventorySize").equals(Integer.toString(inventory.length))
            && manifest.get("inventorySha256").equals(SignedDeliveryProbe.hash(inventory)), "inventory-integrity");
        String[] rows = ascii(inventory).split("\n", -1);
        require(rows.length >= 2 && rows.length <= 17 && rows[rows.length - 1].isEmpty(), "inventory-count");
        Set<String> expected = new HashSet<>(Arrays.asList("manifest.sig", "inventory.txt"));
        String previous = "";
        int total = 0;
        Map<String, String> roles = new HashMap<>();
        roles.put("code", "code/"); roles.put("resources", "resources/"); roles.put("asset", "assets/");
        roles.put("java-resource", "java/"); roles.put("native", "native/");
        for (int i = 0; i < rows.length - 1; i++) {
            String[] parts = rows[i].split("\\|", -1);
            require(parts.length == 4, "inventory-entry");
            String role = parts[0], path = parts[1], size = parts[2], digest = parts[3];
            require(roles.containsKey(role) && safe(path) && path.startsWith(roles.get(role))
                && path.compareTo(previous) > 0 && size.matches("[1-9][0-9]{0,5}")
                && Integer.parseInt(size) <= ENTRY && digest.matches("[0-9a-f]{64}"), "inventory-entry");
            previous = path;
            require(files.containsKey(path), "component-missing");
            byte[] component = files.get(path);
            require(component.length == Integer.parseInt(size) && SignedDeliveryProbe.hash(component).equals(digest), "component-integrity");
            total += component.length;
            require(total <= TOTAL, "archive-bounds");
            expected.add(path);
        }
        require(expected.equals(files.keySet()), "component-unexpected");
        return files;
    }
}
