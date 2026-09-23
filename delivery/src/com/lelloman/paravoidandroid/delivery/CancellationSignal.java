package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;

/** Shared non-security cancellation epoch. Atomic replacement; never deletes lock files. */
final class CancellationSignal {
    private final Path path;
    CancellationSignal(Path preferences) {
        path = preferences.resolveSibling(preferences.getFileName() + ".cancel");
    }
    String read() throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = new byte[37]; int count = 0, n;
            while (count < bytes.length && (n = in.read(bytes, count, bytes.length - count)) != -1) count += n;
            String value = new String(bytes, 0, count, StandardCharsets.US_ASCII);
            if (!value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IOException("Invalid cancellation epoch");
            return value;
        } catch (NoSuchFileException absent) { return ""; }
    }
    void cancel() throws IOException {
        Files.createDirectories(path.getParent());
        cancelRecord(path);
    }
    // Same-VM serialization prevents a competing descriptor close from releasing
    // a POSIX process lock. All cross-process writers use this permanent lock.
    private static synchronized void cancelRecord(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".write-lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Cancellation writer lock unavailable");
            cancelLocked(path);
        }
    }
    private static void cancelLocked(Path path) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
