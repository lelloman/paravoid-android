package com.lelloman.paravoidandroid.delivery;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Permanent per-path channels: closing a competing descriptor can drop POSIX process locks. */
final class DeliveryLocks {
    private static final Map<Path, Entry> ENTRIES = new HashMap<>();
    private static final class Entry {
        final FileChannel channel;
        boolean held;
        Entry(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
    }
    static synchronized Claim tryAcquire(Path path) throws IOException {
        Path key = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
        Entry entry = ENTRIES.get(key);
        if (entry == null) { entry = new Entry(key); ENTRIES.put(key, entry); }
        if (entry.held) return null;
        final FileLock lock;
        try { lock = entry.channel.tryLock(); }
        catch (OverlappingFileLockException busy) { return null; }
        if (lock == null) return null;
        entry.held = true;
        return new Claim(entry, lock);
    }
    static final class Claim implements AutoCloseable {
        private final Entry entry;
        private final FileLock lock;
        private boolean closed;
        Claim(Entry entry, FileLock lock) { this.entry = entry; this.lock = lock; }
        @Override public void close() throws IOException {
            synchronized (DeliveryLocks.class) {
                if (closed) return;
                lock.release();
                closed = true; entry.held = false;
            }
        }
    }
    private DeliveryLocks() {}
}
