package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
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
        Path tmp = Files.createTempFile(path.getParent(), "cancel", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)); out.getFD().sync();
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(tmp); }
    }
}
