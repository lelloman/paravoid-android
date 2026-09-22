package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class SelectionTest {
    interface Operation<T> { T run(SelectionJournal journal) throws Exception; }
    static <T> T locked(Path root, Operation<T> operation) throws Exception {
        Exception[] failure = new Exception[1];
        T result = ProcessLocks.selection(root.resolve("selection.lock"), () -> {
            try { return operation.run(new SelectionJournal(root)); }
            catch (Exception e) { failure[0] = e; return null; }
        });
        if (failure[0] != null) throw failure[0];
        return result;
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static SelectionJournal.Generation generation(long version) {
        return new SelectionJournal.Generation("generation_" + version, AdmissionTest.release(version));
    }
    static Process child(Path root, String mode) throws Exception {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
            SelectionTest.class.getName(), mode, root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static String line(Process p) throws Exception {
        java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try { return reader.submit(() -> new BufferedReader(new InputStreamReader(p.getInputStream())).readLine()).get(10, TimeUnit.SECONDS); }
        finally { reader.shutdownNow(); }
    }
    static void exit(Process p) throws Exception { check(p.waitFor(10, TimeUnit.SECONDS)); check(p.exitValue() == 0); }
    static void stop(Process p) throws Exception { p.destroyForcibly(); check(p.waitFor(10, TimeUnit.SECONDS)); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Path root = Paths.get(args[1]);
            try {
                SelectionJournal.Generation candidate = locked(root, SelectionJournal::candidate);
                // Private fake verification: no runtime code is loaded by this harness.
                SelectionJournal.Generation acquired = locked(root, j -> j.acquire(candidate));
                if (args[0].equals("background") || args[0].equals("healthy")) locked(root, j -> { j.applicationCreated(acquired); return null; });
                if (args[0].equals("healthy")) locked(root, j -> { j.healthy(acquired); return null; });
                if (args[0].equals("failed")) locked(root, j -> { j.failed(acquired); j.applicationCreated(acquired); j.healthy(acquired); return null; });
                System.out.println(acquired.directory); System.out.flush();
                if (args[0].equals("hold")) Thread.sleep(60000);
            } catch (ContractException e) { System.out.println(e.code); }
            return;
        }
        Path root = Files.createTempDirectory("selection-test-");
        locked(root, j -> { j.initializeNew(); return null; });
        check(locked(root, j -> j.snapshot(0)).availability == Availability.EMPTY);
        check(locked(root, j -> j.pending(generation(1))).status == StageStatus.PENDING);
        check(locked(root, j -> j.snapshot(0)).active == null);
        Process first = child(root, "hold"), worker = null;
        try {
            check(line(first).equals("generation_1"));
            locked(root, j -> j.pending(generation(2)));
            check(locked(root, j -> j.snapshot(0)).waitingForProcesses);
            worker = child(root, "hold"); check(line(worker).equals("generation_1"));
            stop(first);
            Process join = child(root, "background"); check(line(join).equals("generation_1")); exit(join);
            check(locked(root, j -> j.snapshot(0)).availability == Availability.TRIAL);
            stop(worker);
        } finally { first.destroyForcibly(); if (worker != null) worker.destroyForcibly(); }
        Process next = child(root, "healthy"); check(line(next).equals("generation_2")); exit(next);
        LifecycleSnapshot healthy = locked(root, j -> j.snapshot(0));
        check(healthy.availability == Availability.RUNNABLE && healthy.active.payloadVersion == 2 && healthy.pending == null);
        locked(root, j -> j.pending(generation(3)));
        for (int i = 0; i < 2; i++) { Process incomplete = child(root, "incomplete"); check(line(incomplete).equals("generation_3")); exit(incomplete); }
        Process paused = child(root, "incomplete"); check(line(paused).equals("UNAVAILABLE")); exit(paused);
        LifecycleSnapshot recovery = locked(root, j -> j.snapshot(0));
        check(recovery.availability == Availability.RECOVERY && recovery.active.payloadVersion == 3 && recovery.lastHealthy.payloadVersion == 2);
        check(locked(root, j -> j.protectedDirectories()).contains("generation_2"));
        locked(root, j -> { j.retain(0); return null; });
        check(!locked(root, j -> j.protectedDirectories()).contains("generation_2"));
        check(locked(root, j -> j.protectedDirectories()).contains("generation_3"));
        locked(root, j -> { j.retry(generation(3)); return null; });
        Process failed = child(root, "failed"); check(line(failed).equals("generation_3")); exit(failed);
        check(locked(root, j -> j.snapshot(0)).availability == Availability.RECOVERY); // success cannot undo caught failure
        locked(root, j -> j.pending(generation(4)));
        Process repair = child(root, "healthy"); check(line(repair).equals("generation_4")); exit(repair);
        check(locked(root, j -> j.snapshot(0)).active.payloadVersion == 4);
        locked(root, j -> j.pending(generation(5)));
        SelectionJournal.Generation stale = locked(root, SelectionJournal::candidate);
        locked(root, j -> j.pending(generation(6)));
        AdmissionTest.fails(Code.UNAVAILABLE, () -> locked(root, j -> j.acquire(stale)));
        locked(root, j -> { j.rejectPending(generation(6)); return null; });
        check(locked(root, j -> j.snapshot(0)).active.payloadVersion == 4);
        AdmissionTest.fails(Code.REPLAY, () -> locked(root, j -> j.pending(generation(2))));
        byte[] bytes = Files.readAllBytes(root.resolve("selection")); bytes[15] ^= 1; Files.write(root.resolve("selection"), bytes);
        AdmissionTest.fails(Code.CORRUPT_STATE, () -> locked(root, j -> j.snapshot(0)));
        System.out.println("PASS selection/trials: pending only, joining/death, cold activation, background progress, two incomplete attempts, quarantine, forward repair, stale verification, retention, corrupt journal");
    }
}
