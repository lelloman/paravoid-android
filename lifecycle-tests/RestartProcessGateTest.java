package com.lelloman.paravoidandroid.runtime;

import java.util.*;

/** Deterministic platform snapshots, not Android process/PackageManager evidence. */
public final class RestartProcessGateTest {
    static final class Platform implements RestartProcessGate.Platform {
        List<RestartProcessGate.Entry> entries = new ArrayList<>();
        final List<Integer> killed = new ArrayList<>();
        boolean exclusive = true, stuck, respawn, denied;
        long clock; int reads;
        Runnable beforeRead = () -> {};
        public boolean exclusiveUid() { return exclusive; }
        public List<RestartProcessGate.Entry> processes() { reads++; beforeRead.run(); return entries == null ? null : new ArrayList<>(entries); }
        public void kill(int pid) {
            if (denied) throw new SecurityException();
            killed.add(pid);
            if (!stuck) entries.removeIf(p -> p.pid == pid);
            if (respawn) entries.add(own(99));
        }
        public long elapsed() { return clock; }
        public void sleep() { clock += 100; }
        RestartProcessGate gate() { return new RestartProcessGate(this, 1000, 1, "app"); }
    }
    static RestartProcessGate.Entry own(int pid) { return new RestartProcessGate.Entry(pid, 1000, new String[]{"app"}); }
    static Platform fixture() {
        Platform p = new Platform(); p.entries.add(own(1)); p.entries.add(own(2)); p.entries.add(own(3));
        p.entries.add(new RestartProcessGate.Entry(4, 2000, new String[]{"other"})); return p;
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Platform normal = fixture();
        check(normal.gate().stop() && normal.gate().readyToLaunch());
        check(normal.killed.equals(Arrays.asList(2, 3)));
        normal.entries.add(own(99));
        check(!normal.gate().readyToLaunch()); // Respawn while launch callback waited on main thread.
        for (boolean respawn : new boolean[]{false, true}) {
            Platform p = fixture(); p.stuck = !respawn; p.respawn = respawn;
            check(!p.gate().stop() && p.clock == 5000);
            check(p.killed.equals(Arrays.asList(2, 3)) && !p.gate().readyToLaunch());
        }
        Platform shared = fixture(); shared.exclusive = false;
        check(!shared.gate().stop() && shared.killed.isEmpty()); // Even if foreign package has no process.
        Platform unknown = fixture(); unknown.entries = null;
        check(!unknown.gate().stop() && !unknown.gate().readyToLaunch() && unknown.killed.isEmpty());
        for (String[] owners : new String[][]{null, {}, {"other"}, {"app", "other"}}) {
            Platform p = fixture(); p.entries.add(new RestartProcessGate.Entry(5, 1000, owners));
            check(!p.gate().stop() && p.killed.isEmpty()); // Validate every target before first kill.
        }
        Platform denied = fixture(); denied.denied = true;
        check(!denied.gate().stop() && denied.killed.isEmpty());
        Platform reused = fixture();
        reused.beforeRead = () -> {
            if (reused.reads == 2) {
                reused.entries.removeIf(p -> p.pid == 2);
                reused.entries.add(new RestartProcessGate.Entry(2, 2000, new String[]{"other"}));
            }
        };
        check(reused.gate().stop() && reused.killed.equals(Collections.singletonList(3)));
        Platform changed = fixture();
        changed.beforeRead = () -> { if (changed.reads == 2) changed.entries.add(new RestartProcessGate.Entry(5, 1000, null)); };
        check(!changed.gate().stop() && changed.killed.isEmpty());
        Platform late = fixture(); check(late.gate().stop()); late.exclusive = false;
        check(!late.gate().readyToLaunch());
        System.out.println("PASS restart gate: timeout, respawn, launch recheck, shared/unknown ownership, denial, stale PID and recovery exclusion");
    }
}
