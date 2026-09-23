package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Single update writer, across download and embedded/downloaded preparation. No selection lock. */
final class SpaceAdmission {
    static final long HEADROOM = 64L * 1024 * 1024;
    private static final Map<Path, Entry> ENTRIES = new HashMap<>();
    private static final class Entry {
        final FileChannel channel;
        boolean held;
        Entry(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
    }
    static long required(long size, int copies) throws ContractException {
        if (size < 1 || size > Protocol.MAX_ARCHIVE_BYTES || copies < 2 || copies > 3)
            throw fail(Code.LIMIT_EXCEEDED);
        return size * copies + HEADROOM; // MAX_ARCHIVE_BYTES bounds arithmetic well below Long.MAX_VALUE.
    }
    static synchronized Claim acquire(Path root) throws ContractException {
        ProcessLocks.requireOutsideSelection();
        try {
            Path key = root.toRealPath().resolve("update-space.lock");
            Entry entry = ENTRIES.get(key);
            if (entry == null) { entry = new Entry(key); ENTRIES.put(key, entry); }
            if (entry.held) throw fail(Code.UNAVAILABLE);
            FileLock lock = entry.channel.tryLock();
            if (lock == null) throw fail(Code.UNAVAILABLE);
            entry.held = true;
            return new Claim(entry, lock);
        } catch (IOException failure) { throw fail(Code.IO); }
        // Channels deliberately live for the process. Closing a competing descriptor can
        // release POSIX locks owned by this process; failed claims must not open/close one.
    }
    static final class Claim implements AutoCloseable {
        private final Entry entry;
        private final FileLock lock;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        Claim(Entry entry, FileLock lock) { this.entry = entry; this.lock = lock; }
        void check() throws ContractException {
            if (closed || Thread.currentThread() != owner) throw fail(Code.UNAVAILABLE);
        }
        @Override public void close() throws ContractException {
            synchronized (SpaceAdmission.class) {
                if (closed) return;
                check();
                try { lock.release(); }
                catch (IOException failure) { throw fail(Code.IO); }
                closed = true; entry.held = false;
            }
        }
    }
    private static ContractException fail(Code code) { return new ContractException(code, "Update space: " + code.name()); }
}
