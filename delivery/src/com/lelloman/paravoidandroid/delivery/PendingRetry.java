package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Properties;

/** Non-security scheduling state, protected by the controller's attempt lock. */
final class PendingRetry {
    final String partition;
    final long dueSeconds;
    final int retries;
    final boolean explicit;
    final String cancellationEpoch;
    PendingRetry(String partition, long dueSeconds, int retries, boolean explicit) {
        this(partition, dueSeconds, retries, explicit, "");
    }
    PendingRetry(String partition, long dueSeconds, int retries, boolean explicit, String cancellationEpoch) {
        this.partition = partition; this.dueSeconds = dueSeconds; this.retries = retries; this.explicit = explicit;
        this.cancellationEpoch = cancellationEpoch;
    }
    static PendingRetry read(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        if (Files.size(path) > 4096) throw new IOException("Invalid retry state");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        try {
            String partition = p.getProperty("partition", "");
            long due = Long.parseLong(p.getProperty("due"));
            int retries = Integer.parseInt(p.getProperty("retries"));
            String explicit = p.getProperty("explicit", "");
            if (!partition.matches("[0-9a-f]{64}") || due < 0 || retries < 1 || retries > 4
                    || !(explicit.equals("true") || explicit.equals("false"))) throw new IllegalArgumentException();
            String epoch = p.getProperty("cancellationEpoch", "");
            if (!epoch.isEmpty() && !epoch.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IllegalArgumentException();
            return new PendingRetry(partition, due, retries, Boolean.parseBoolean(explicit), epoch);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid retry state"); }
    }
    void write(Path path) throws IOException {
        Properties p = new Properties();
        p.setProperty("partition", partition); p.setProperty("due", Long.toString(dueSeconds));
        p.setProperty("retries", Integer.toString(retries)); p.setProperty("explicit", Boolean.toString(explicit));
        p.setProperty("cancellationEpoch", cancellationEpoch);
        writeRecord(path, p);
    }
    // Serialize same-VM and cross-process writers before reusing a bounded slot.
    // The permanent lock file must never be unlinked, including on recovery.
    private static synchronized void writeRecord(Path path, Properties p) throws IOException {
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".write-lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Retry writer lock unavailable");
            writeLocked(path, p);
        }
    }
    private static void writeLocked(Path path, Properties p) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                p.store(out, "Delivery retry schedule");
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
