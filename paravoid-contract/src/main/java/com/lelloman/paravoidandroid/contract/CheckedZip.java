package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Structural ZIP validation over a bounded file slice, without extraction or archive-sized allocations. */
final class CheckedZip {
    static final class Entry {
        final String name;
        final long local, data, end, compressed, size, crc;
        final int method;
        Entry(String name, long local, long data, long end, long compressed, long size, long crc, int method) {
            this.name = name; this.local = local; this.data = data; this.end = end;
            this.compressed = compressed; this.size = size; this.crc = crc; this.method = method;
        }
    }
    final RandomAccessFile file;
    final long base, length;
    final Map<String,Entry> entries = new LinkedHashMap<>();
    CheckedZip(RandomAccessFile file, long base, long length, boolean outer, int maxEntries) throws IOException, ContractException {
        this.file = file; this.base = base; this.length = length;
        if (length < 22) throw bad();
        long end = -1;
        for (long p = length - 22, min = Math.max(0, length - 65557); p >= min; p--) {
            if (u32(p) == 0x06054b50L && p + 22 + u16(p + 20) == length) { end = p; break; }
        }
        if (end < 0 || u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)
                || (outer && u16(end + 20) != 0)) throw bad();
        int count = u16(end + 10);
        long directorySize = u32(end + 12), directory = u32(end + 16);
        if (count == 65535 || count > maxEntries) throw limit();
        if (directory + directorySize != end) throw bad();
        long cursor = directory;
        List<Entry> positions = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            if (cursor + 46 > end || u32(cursor) != 0x02014b50L) throw bad();
            int needed = u16(cursor + 6), flags = u16(cursor + 8), method = u16(cursor + 10);
            int time = u16(cursor + 12), date = u16(cursor + 14);
            long crc = u32(cursor + 16), compressed = u32(cursor + 20), size = u32(cursor + 24);
            int nameSize = u16(cursor + 28), extraSize = u16(cursor + 30), commentSize = u16(cursor + 32);
            long attrs = u32(cursor + 38), local = u32(cursor + 42);
            if (needed > 20 || (flags & ~(0x800 | 8 | 6)) != 0 || (method != 0 && method != 8)
                    || (method == 0 && (flags & 6) != 0) || u16(cursor + 34) != 0
                    || compressed == 0xffffffffL || size == 0xffffffffL || local == 0xffffffffL
                    || nameSize == 0 || nameSize > 255 || cursor + 46 + nameSize + extraSize + commentSize > end
                    || (outer && (method != 0 || (flags & 8) != 0 || extraSize != 0 || commentSize != 0))) throw bad();
            int unixType = (int)((attrs >>> 16) & 0170000);
            if (unixType != 0 && unixType != 0100000 && unixType != 0040000) throw bad();
            byte[] nameBytes = bytes(cursor + 46, nameSize);
            String name = name(nameBytes, outer);
            if ((outer && name.endsWith("/")) || ((attrs & 16) != 0 && !name.endsWith("/"))
                    || (unixType == 0040000 && !name.endsWith("/")) || entries.containsKey(name)) throw bad();
            extras(cursor + 46 + nameSize, extraSize, false);
            if (local + 30 > directory || u32(local) != 0x04034b50L || u16(local + 4) != needed
                    || u16(local + 6) != flags || u16(local + 8) != method || u16(local + 10) != time || u16(local + 12) != date
                    || u16(local + 26) != nameSize || !Arrays.equals(bytes(local + 30, nameSize), nameBytes)) throw bad();
            int localExtra = u16(local + 28);
            if (outer && localExtra != 0) throw bad();
            extras(local + 30 + nameSize, localExtra, !outer);
            long data = local + 30 + nameSize + localExtra;
            long entryEnd = data + compressed;
            if (entryEnd > directory || (method == 0 && size != compressed)) throw bad();
            if ((flags & 8) != 0) {
                if (!((u32(local + 14) == 0 && u32(local + 18) == 0 && u32(local + 22) == 0)
                        || (u32(local + 14) == crc && u32(local + 18) == compressed && u32(local + 22) == size))) throw bad();
                long descriptor = entryEnd;
                if (u32(descriptor) == 0x08074b50L) descriptor += 4;
                if (descriptor + 12 > directory || u32(descriptor) != crc || u32(descriptor + 4) != compressed || u32(descriptor + 8) != size) throw bad();
                entryEnd = descriptor + 12;
            } else if (u32(local + 14) != crc || u32(local + 18) != compressed || u32(local + 22) != size) throw bad();
            if (name.endsWith("/") && size != 0) throw bad();
            Entry entry = new Entry(name, local, data, entryEnd, compressed, size, crc, method);
            entries.put(name, entry); positions.add(entry);
            cursor += 46 + nameSize + extraSize + commentSize;
        }
        if (cursor != end) throw bad();
        positions.sort(Comparator.comparingLong(e -> e.local));
        long expected = 0;
        for (Entry entry : positions) { if (entry.local != expected) throw bad(); expected = entry.end; }
        if (expected != directory) throw bad(); // no hidden/prefixed/overlapping bytes or signing blocks
        for (String path : entries.keySet()) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (entries.containsKey(path.substring(0, slash))) throw bad();
                slash = path.indexOf('/', slash + 1);
            }
        }
    }
    byte[] read(Entry entry, int maximum) throws IOException, ContractException {
        if (entry == null) throw bad();
        if (entry.size > maximum) throw limit();
        ByteArrayOutputStream out = new ByteArrayOutputStream((int)entry.size);
        check(entry, entry.size, null, out);
        return out.toByteArray();
    }
    byte[] prefix(Entry entry, int length) throws IOException, ContractException {
        if (entry == null || entry.size < length) throw bad();
        Slice source = new Slice(file, base + entry.data, entry.compressed);
        Inflater inflater = entry.method == 8 ? new Inflater(true) : null;
        InputStream stream = inflater == null ? source : new InflaterInputStream(source, inflater, 8192);
        try { byte[] out = new byte[length]; new DataInputStream(stream).readFully(out); return out; }
        finally { if (inflater != null) inflater.end(); }
    }
    void check(Entry entry, long maximum, String expectedHash, OutputStream out) throws IOException, ContractException {
        if (entry.size > maximum) throw limit();
        CRC32 crc = new CRC32();
        MessageDigest digest = sha();
        Slice source = new Slice(file, base + entry.data, entry.compressed);
        Inflater inflater = entry.method == 8 ? new Inflater(true) : null;
        InputStream stream = inflater == null ? source : new InflaterInputStream(source, inflater, 8192);
        long total = 0;
        try {
            byte[] buffer = new byte[8192]; int n;
            while ((n = stream.read(buffer)) != -1) {
                total += n;
                if (total > entry.size || total > maximum) throw limit();
                crc.update(buffer, 0, n); digest.update(buffer, 0, n);
                if (out != null) out.write(buffer, 0, n);
            }
            if (total != entry.size || crc.getValue() != entry.crc ||
                    (inflater != null && (!inflater.finished() || inflater.getBytesRead() != entry.compressed))) throw bad();
            if (expectedHash != null && !expectedHash.equals(hex(digest.digest()))) throw new ContractException(INTEGRITY, "Component digest mismatch");
        } catch (ZipException malformed) { throw bad(); }
        finally { if (inflater != null) inflater.end(); }
    }
    private void extras(long at, int size, boolean localPadding) throws IOException, ContractException {
        long end = at + size;
        while (at < end) {
            if (at + 4 > end) {
                // Android zipalign appends up to three zero bytes to a local extra
                // area for four-byte alignment. Never accept this in central or outer headers.
                if (!localPadding) throw bad();
                for (byte b : bytes(at, (int)(end - at))) if (b != 0) throw bad();
                return;
            }
            int id = u16(at), n = u16(at + 2);
            if (id == 1 || at + 4 + n > end) throw bad(); // ZIP64 forbidden even without sentinels
            at += 4 + n;
        }
    }
    private String name(byte[] bytes, boolean ascii) throws ContractException {
        if (ascii) for (byte b : bytes) if (b < 32 || b > 126) throw bad();
        String name;
        try { name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw bad(); }
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) throw bad();
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 32 || name.charAt(i) == 127) throw bad();
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw bad();
        return name;
    }
    private int u16(long at) throws IOException, ContractException { byte[] b = bytes(at, 2); return (b[0] & 255) | ((b[1] & 255) << 8); }
    private long u32(long at) throws IOException, ContractException {
        byte[] b = bytes(at, 4); return (b[0] & 255L) | ((b[1] & 255L) << 8) | ((b[2] & 255L) << 16) | ((b[3] & 255L) << 24);
    }
    private byte[] bytes(long at, int count) throws IOException, ContractException {
        if (at < 0 || at > length - count) throw bad();
        byte[] out = new byte[count]; file.seek(base + at); file.readFully(out); return out;
    }
    static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    static final class Slice extends InputStream {
        final RandomAccessFile file; long position, remaining;
        Slice(RandomAccessFile file, long position, long remaining) { this.file = file; this.position = position; this.remaining = remaining; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b) == -1 ? -1 : b[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return 0; if (remaining == 0) return -1;
            int n = (int)Math.min(count, remaining);
            file.seek(position); file.readFully(bytes, offset, n); position += n; remaining -= n; return n;
        }
    }
    private static ContractException bad() { return new ContractException(MALFORMED, "Invalid or unsupported ZIP structure"); }
    private static ContractException limit() { return new ContractException(LIMIT_EXCEEDED, "ZIP content limit"); }
}
