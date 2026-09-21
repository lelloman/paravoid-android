package com.lelloman.paravoidandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Fixture-only reader, in the shell's parent loader. Does NOT authenticate a record. */
public final class ApkProvisioningProbe {
    private static final long RECORD_ID = 0x50564131L;

    public static byte[] read(File apk) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(apk, "r")) {
            long length = file.length();
            long end = -1;
            for (long offset = Math.max(0, length - 65557); offset <= length - 22; offset++) {
                file.seek(offset);
                if (u32(file) == 0x06054b50L) {
                    file.seek(offset + 20);
                    if (offset + 22 + u16(file) == length) {
                        if (end != -1) throw new IOException("Ambiguous ZIP end record");
                        end = offset;
                    }
                }
            }
            if (end < 0) throw new IOException("ZIP end record missing");
            file.seek(end + 4);
            if (u16(file) != 0 || u16(file) != 0) throw new IOException("Multi-disk ZIP");
            int diskCount = u16(file), count = u16(file);
            long size = u32(file), directory = u32(file);
            if (diskCount != count || count == 65535 || directory + size != end || directory < 32)
                throw new IOException("Unsupported ZIP layout");
            file.seek(directory - 24);
            long blockSize = u64(file);
            byte[] magic = new byte[16];
            file.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("APK Sig Block 42"))
                throw new IOException("Signing block missing");
            long start = directory - blockSize - 8;
            if (blockSize < 24 || blockSize > 16 * 1024 * 1024 || start < 0)
                throw new IOException("Invalid block size");
            file.seek(start);
            if (u64(file) != blockSize) throw new IOException("Block sizes disagree");
            byte[] result = null;
            Set<Long> ids = new HashSet<>();
            while (file.getFilePointer() < directory - 24) {
                if (file.getFilePointer() + 8 > directory - 24) throw new IOException("Truncated entry");
                long pairSize = u64(file);
                long remaining = directory - 24 - file.getFilePointer();
                if (pairSize < 4 || pairSize > remaining) throw new IOException("Invalid pair size");
                long id = u32(file);
                if (!ids.add(id)) throw new IOException("Duplicate entry");
                if (id == RECORD_ID) {
                    if (pairSize - 4 > 4096) throw new IOException("Record too large");
                    result = new byte[(int) pairSize - 4];
                    file.readFully(result);
                } else file.seek(file.getFilePointer() + pairSize - 4);
            }
            return result;
        }
    }

    private static int u16(RandomAccessFile file) throws IOException {
        return file.readUnsignedByte() | (file.readUnsignedByte() << 8);
    }
    private static long u32(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
    }
    private static long u64(RandomAccessFile file) throws IOException {
        long value = Long.reverseBytes(file.readLong());
        if (value < 0) throw new IOException("Oversized unsigned integer");
        return value;
    }
}
