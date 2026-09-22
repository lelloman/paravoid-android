package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.contract.Protocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded opaque carrier extraction, not grant authentication or an APK signature verifier. */
public final class ApkGrantReader {
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_BLOCK = 16L << 20;
    private ApkGrantReader() {}

    /** Read the installed base APK on every process startup. Never fall back to an asset/cache. */
    public static byte[] read(File installedBaseApk) throws IOException, ContractException {
        try (RandomAccessFile apk = new RandomAccessFile(installedBaseApk, "r")) {
            long length = apk.length();
            if (length < 22 || length > 0xffffffffL) throw malformed();
            int tailSize = (int) Math.min(length, 65557);
            byte[] tail = new byte[tailSize]; apk.seek(length - tailSize); apk.readFully(tail);
            int endIndex = -1;
            for (int i = 0; i <= tailSize - 22; i++) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tailSize) {
                    if (endIndex >= 0) throw malformed(); endIndex = i;
                }
            }
            if (endIndex < 0) throw malformed();
            long end = length - tailSize + endIndex;
            int count = u16(tail, endIndex + 10);
            long directorySize = u32(tail, endIndex + 12), directory = u32(tail, endIndex + 16);
            if (u16(tail, endIndex + 4) != 0 || u16(tail, endIndex + 6) != 0
                    || u16(tail, endIndex + 8) != count || count == 65535
                    || directorySize == 0xffffffffL || directory == 0xffffffffL
                    || directory + directorySize != end || directory < 32) throw malformed();
            apk.seek(directory - 24);
            long blockSize = u64(apk);
            byte[] magic = new byte[16]; apk.readFully(magic);
            if (!Arrays.equals(MAGIC, magic) || blockSize < 24 || blockSize > MAX_BLOCK
                    || directory < blockSize + 8) throw malformed();
            long start = directory - blockSize - 8;
            apk.seek(start);
            if (u64(apk) != blockSize) throw malformed();
            Set<Long> ids = new HashSet<>();
            byte[] grant = null;
            while (apk.getFilePointer() < directory - 24) {
                if (directory - 24 - apk.getFilePointer() < 12) throw malformed();
                long pairSize = u64(apk);
                if (pairSize < 4 || pairSize > directory - 24 - apk.getFilePointer()) throw malformed();
                long id = Integer.toUnsignedLong(Integer.reverseBytes(apk.readInt()));
                if (!ids.add(id)) throw malformed();
                if (id == Integer.toUnsignedLong(Protocol.GRANT_BLOCK_ID)) {
                    if (pairSize <= 4 || pairSize - 4 > Protocol.MAX_GRANT_BYTES)
                        throw new ContractException(ContractException.Code.LIMIT_EXCEEDED, "Invalid APK grant size");
                    grant = new byte[(int) pairSize - 4]; apk.readFully(grant);
                } else apk.seek(apk.getFilePointer() + pairSize - 4);
            }
            if (!ids.contains(0x7109871aL) && !ids.contains(0xf05368c0L)) throw malformed();
            if (grant == null) throw new ContractException(ContractException.Code.CREDENTIAL_UNAVAILABLE, "APK update credential missing");
            return grant;
        } catch (EOFException truncated) { throw malformed(); }
    }
    private static long u64(RandomAccessFile input) throws IOException, ContractException {
        long value = Long.reverseBytes(input.readLong());
        if (value < 0) throw malformed(); return value;
    }
    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
    }
    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8
                | (bytes[offset + 2] & 255) << 16 | (bytes[offset + 3] & 255) << 24);
    }
    private static ContractException malformed() {
        return new ContractException(ContractException.Code.MALFORMED, "Unsupported or malformed APK signing block");
    }
}
