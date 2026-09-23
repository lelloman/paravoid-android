package com.lelloman.paravoidandroid.runtime;

import java.util.List;

/** Conservative restart decisions; no journal changes or synthetic lease release. */
final class RestartProcessGate {
    static final class Entry {
        final int pid, uid;
        final String[] packages;
        Entry(int pid, int uid, String[] packages) { this.pid = pid; this.uid = uid; this.packages = packages; }
    }
    interface Platform {
        boolean exclusiveUid();
        List<Entry> processes();
        void kill(int pid);
        long elapsed();
        void sleep();
    }
    private final Platform platform;
    private final int uid, self;
    private final String app;
    RestartProcessGate(Platform platform, int uid, int self, String app) {
        this.platform = platform; this.uid = uid; this.self = self; this.app = app;
    }
    private boolean target(Entry entry) { return entry.uid == uid && entry.pid != self; }
    private List<Entry> checked() {
        if (!platform.exclusiveUid()) throw new IllegalStateException("Shared UID");
        List<Entry> entries = platform.processes();
        if (entries == null) throw new IllegalStateException("Process list unavailable");
        for (Entry entry : entries) if (target(entry) && (entry.pid <= 0 || entry.packages == null
                || entry.packages.length != 1 || !app.equals(entry.packages[0])))
            throw new IllegalStateException("Process ownership unavailable");
        return entries;
    }
    boolean stop() {
        try {
            List<Entry> initial = checked();
            for (Entry entry : initial) if (target(entry)) {
                // Re-resolve immediately before signaling; do not act on a stale PID list.
                for (Entry current : checked()) if (current.pid == entry.pid && target(current)) {
                    platform.kill(current.pid); break;
                }
            }
            long started = platform.elapsed();
            while (platform.elapsed() - started < 5000) {
                if (checked().stream().noneMatch(this::target)) return true;
                // Respawn is not permission for an unbounded kill loop. Leave recovery usable.
                platform.sleep();
            }
        } catch (RuntimeException unavailable) { /* Fail closed; no platform/payload error text. */ }
        return false;
    }
    boolean readyToLaunch() {
        try { return checked().stream().noneMatch(this::target); }
        catch (RuntimeException unavailable) { return false; }
    }
}
