package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class StorageTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void fails(ProcessLocks.Operation<Void> action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { }
    }
    private static boolean unleased(Path path, ProcessLocks.Operation<Void> action) throws IOException {
        return ProcessLocks.selection(path.getParent().resolve("selection.lock"), () -> ProcessLocks.ifUnleased(path, action));
    }
    private static boolean remove(OwnedArchive archive, Set<Path> protectedPaths, Path lease) throws IOException {
        return ProcessLocks.selection(lease.getParent().resolve("selection.lock"), () -> archive.removeIfUnprotected(protectedPaths, lease));
    }
    private static Process child(String mode, Path root) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), StorageTest.class.getName(), mode, root.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static void ready(Process process) throws Exception {
        java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String line = reader.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream())).readLine())
                .get(10, TimeUnit.SECONDS);
            check("ready".equals(line));
        } finally { reader.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Path root = Paths.get(args[1]);
            if (args[0].equals("lease")) {
                ProcessLocks.selection(root.resolve("selection.lock"), () -> {
                    ProcessLocks.leaseForProcess(root.resolve("a.lock")); return null;
                });
                System.out.println("ready"); System.out.flush();
                Thread.sleep(60000);
            } else if (args[0].equals("increment")) {
                for (int i = 0; i < 40; i++) ProcessLocks.selection(root.resolve("selection.lock"), () -> {
                    AtomicRecord record = new AtomicRecord(root.resolve("counter"));
                    int value = java.nio.ByteBuffer.wrap(record.read()).getInt();
                    record.write(java.nio.ByteBuffer.allocate(4).putInt(value + 1).array()); return null;
                });
            } else {
                new AtomicRecord(root.resolve("record"), boundary -> {
                    if (boundary.equals(args[0])) Runtime.getRuntime().halt(73);
                }).write(new byte[] { 2 });
            }
            return;
        }
        Path root = Files.createTempDirectory("lifecycle-test-");
        AtomicRecord record = new AtomicRecord(root.resolve("record"));
        fails(() -> { record.read(); return null; });
        record.write(new byte[] { 1 }); check(Arrays.equals(record.read(), new byte[] { 1 }));
        for (String point : Arrays.asList("content-synced", "renamed", "directory-synced")) {
            record.write(new byte[] { 1 });
            Process process = child(point, root);
            check(process.waitFor(10, TimeUnit.SECONDS)); check(process.exitValue() == 73);
            check(record.read()[0] == (point.equals("content-synced") ? 1 : 2));
        }
        byte[] valid = Files.readAllBytes(root.resolve("record"));
        for (int length = 0; length < valid.length; length++) {
            Files.write(root.resolve("record"), Arrays.copyOf(valid, length));
            fails(() -> { record.read(); return null; });
        }
        for (int index = 0; index < valid.length; index++) {
            byte[] corrupt = valid.clone(); corrupt[index] ^= 1;
            Files.write(root.resolve("record"), corrupt);
            fails(() -> { record.read(); return null; });
        }
        fails(() -> { record.write(new byte[AtomicRecord.MAX_BYTES + 1]); return null; });
        fails(() -> { new AtomicRecord(root.resolve("missing/record")).write(new byte[0]); return null; });
        record.write(new byte[] { 1 });
        fails(() -> { new AtomicRecord(root.resolve("record"), boundary -> {
            if (boundary.equals("content-synced")) throw new IOException("injected storage failure");
        }).write(new byte[] { 2 }); return null; });
        check(record.read()[0] == 1);
        System.out.println("PASS atomic records: process interruption, every-byte corruption/truncation, bounded reads, storage failure");

        Process one = child("lease", root), two = child("lease", root);
        try {
            ready(one); ready(two);
            check(!unleased(root.resolve("a.lock"), () -> { throw new AssertionError(); }));
            one.destroyForcibly(); check(one.waitFor(10, TimeUnit.SECONDS));
            check(!unleased(root.resolve("a.lock"), () -> { throw new AssertionError(); }));
            two.destroyForcibly(); check(two.waitFor(10, TimeUnit.SECONDS));
            check(unleased(root.resolve("a.lock"), () -> null));
        } finally { one.destroyForcibly(); two.destroyForcibly(); }
        ProcessLocks.selection(root.resolve("selection.lock"), () -> {
            ProcessLocks.leaseForProcess(root.resolve("b.lock")); return null;
        });
        ProcessLocks.selection(root.resolve("selection.lock"), () -> {
            ProcessLocks.leaseForProcess(root.resolve("b.lock")); return null;
        });
        check(!unleased(root.resolve("b.lock"), () -> { throw new AssertionError(); }));
        System.out.println("PASS shared leases: two processes, partial death, final death, repeated local acquisition");
        new AtomicRecord(root.resolve("counter")).write(new byte[4]);
        List<Process> racers = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) racers.add(child("increment", root));
            for (Process process : racers) { check(process.waitFor(20, TimeUnit.SECONDS)); check(process.exitValue() == 0); }
        } finally { for (Process process : racers) process.destroyForcibly(); }
        check(java.nio.ByteBuffer.wrap(new AtomicRecord(root.resolve("counter")).read()).getInt() == 160);
        System.out.println("PASS selection journal races: four processes, 160 durable transactions");
        Path staging = Files.createDirectory(root.resolve("staging"));
        Path accepted = Files.createDirectory(root.resolve("accepted"));
        byte[] download = { 3, 4, 5 };
        byte[] identity = AtomicRecord.hash(download);
        OwnedArchive.Verification fake = owned -> check(Arrays.equals(Files.readAllBytes(owned), new byte[] {3, 4, 5}));
        OwnedArchive prepared = OwnedArchive.prepare(staging, new ByteArrayInputStream(download), 3, identity, fake);
        download[0] = 9; // Transport can mutate its bytes after handoff without affecting owned bytes.
        OwnedArchive published = ProcessLocks.selection(root.resolve("selection.lock"), () -> prepared.publish(accepted));
        check(!Files.exists(prepared.path));
        published.reverify(fake);
        fails(() -> { OwnedArchive.prepare(staging, new ByteArrayInputStream(download), 3, identity, fake); return null; });
        fails(() -> { OwnedArchive.prepare(staging, new ByteArrayInputStream(new byte[] {3,4,5}), 2, identity, fake); return null; });
        fails(() -> { OwnedArchive.prepare(staging, new ByteArrayInputStream(new byte[] {3,4}), 3, identity, fake); return null; });
        fails(() -> { OwnedArchive.prepare(staging, new ByteArrayInputStream(new byte[] {3,4,5}), 3, identity,
            owned -> { throw new IOException("fake verifier rejection"); }); return null; });
        try (java.util.stream.Stream<Path> files = Files.list(staging)) { check(files.count() == 0); }
        check(!remove(published, Collections.singleton(published.path), root.resolve("a.lock")));
        Process holder = child("lease", root);
        try {
            ready(holder);
            check(!remove(published, Collections.emptySet(), root.resolve("a.lock")));
        } finally { holder.destroyForcibly(); check(holder.waitFor(10, TimeUnit.SECONDS)); }
        Files.setPosixFilePermissions(published.path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        fails(() -> { published.reverify(fake); return null; });
        Files.write(published.path, new byte[] { 8, 4, 5 });
        Files.setPosixFilePermissions(published.path, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        fails(() -> { published.reverify(fake); return null; });
        check(remove(published, Collections.emptySet(), root.resolve("a.lock")));
        check(!Files.exists(published.path));
        System.out.println("PASS owned archives: private copy, fake verification, read-only publication, bounds/corruption, protected/leased cleanup");
    }
}
