package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Local damage-detecting framing; not a security metadata parser. Caller holds its OS lock. */
final class AtomicRecord {
    private static final int MAGIC = 0x50564c31, HEADER = 12, HASH = 32;
    static final int MAX_BYTES = 1024 * 1024;
    interface Fault { void at(String boundary) throws IOException; }
    private final Path path;
    private final Fault fault;

    AtomicRecord(Path path) { this(path, boundary -> {}); }
    AtomicRecord(Path path, Fault fault) { this.path = path; this.fault = fault; }

    byte[] read() throws IOException {
        // A missing record is an error, never an invitation to reset security history.
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = input.size();
            if (size < HEADER + HASH || size > HEADER + HASH + MAX_BYTES)
                throw new IOException("Invalid lifecycle record size");
            ByteBuffer data = ByteBuffer.allocate((int) size);
            while (data.hasRemaining()) if (input.read(data) < 0) throw new IOException("Truncated lifecycle record");
            if (input.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Growing lifecycle record");
            byte[] bytes = data.array();
            data.flip();
            if (data.getInt() != MAGIC || data.getInt() != 1 || data.getInt() != size - HEADER - HASH)
                throw new IOException("Unknown or corrupt lifecycle record");
            if (!MessageDigest.isEqual(hash(Arrays.copyOf(bytes, bytes.length - HASH)),
                    Arrays.copyOfRange(bytes, bytes.length - HASH, bytes.length)))
                throw new IOException("Lifecycle record checksum mismatch");
            return Arrays.copyOfRange(bytes, HEADER, bytes.length - HASH);
        }
    }

    void write(byte[] payload) throws IOException {
        if (payload.length > MAX_BYTES) throw new IOException("Lifecycle record exceeds limit");
        ByteBuffer data = ByteBuffer.allocate(HEADER + payload.length + HASH);
        data.putInt(MAGIC).putInt(1).putInt(payload.length).put(payload);
        data.put(hash(Arrays.copyOf(data.array(), HEADER + payload.length))).flip();
        Path temporary = Files.createTempFile(path.getParent(), ".record-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            fault.at("content-synced");
            // Never fall back to a non-atomic replacement.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault.at("renamed");
            syncDirectory(path.getParent());
            fault.at("directory-synced");
        } finally { Files.deleteIfExists(temporary); }
    }

    static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
