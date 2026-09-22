package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Permanent lock files. Channels are retained until OS process exit, never unlinked. */
final class ProcessLocks {
    private static final Map<Path, Entry> ENTRIES = new HashMap<>();
    interface Operation<T> { T run() throws IOException; }
    private static final class Entry {
        final FileChannel channel;
        FileLock shared;
        Entry(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
    }
    private static Entry entry(Path path) throws IOException {
        Path key = path.getParent().toRealPath().resolve(path.getFileName());
        Entry entry = ENTRIES.get(key);
        if (entry == null) { entry = new Entry(key); ENTRIES.put(key, entry); }
        return entry;
    }

    static synchronized <T> T selection(Path path, Operation<T> operation) throws IOException {
        Entry entry = entry(path);
        try (FileLock lock = entry.channel.lock()) { return operation.run(); }
    }

    /** Caller is inside selection(). Lease cannot be released by application lifecycle callbacks. */
    static synchronized void leaseForProcess(Path path) throws IOException {
        Entry entry = entry(path);
        if (entry.shared == null) {
            entry.shared = entry.channel.tryLock(0, Long.MAX_VALUE, true);
            if (entry.shared == null) throw new IOException("Generation is exclusively locked");
            if (!entry.shared.isShared()) {
                entry.shared.release();
                entry.shared = null;
                throw new IOException("Filesystem does not support shared generation leases");
            }
        }
    }

    /** Under selection(), activation/cleanup proceeds only if this nonblocking check succeeds. */
    static synchronized boolean ifUnleased(Path path, Operation<Void> operation) throws IOException {
        Entry entry = entry(path);
        if (entry.shared != null) return false;
        try (FileLock lock = entry.channel.tryLock()) {
            if (lock == null) return false;
            operation.run();
            return true;
        }
    }
}
