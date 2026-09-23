package com.lelloman.paravoidandroid.delivery;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real child-process death at production write boundaries; no simulated filesystem. */
public final class PersistenceDeathTest {
    private static final String PARTITION = "a".repeat(64);
    private static final String[] BOUNDARIES = {"created", "written", "synced", "renamed", "directory-synced"};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Path prefs = Paths.get(args[2]);
            CancellationSignal signal = new CancellationSignal(prefs);
            Path retry = prefs.resolveSibling("preferences.retry");
            if (args[0].equals("write")) {
                if (args[1].equals("retry")) new PendingRetry(PARTITION, 1234, 3, true, signal.read()).write(retry);
                else signal.cancel();
            } else if (args[0].equals("delete")) {
                try (DeliveryControllerTest.Control control = new DeliveryControllerTest.Control(false, prefs)) {
                    control.controller.foreground(true);
                    control.await(DeliveryController.Activity.CANCELLED);
                    control.barrier();
                }
            } else {
                PendingRetry value = PendingRetry.read(retry);
                System.out.println(signal.read() + "|" + (value == null ? "absent" : value.retries + ":" + value.cancellationEpoch));
            }
            return;
        }
        Path root = Files.createTempDirectory("delivery-persistence-death-");
        try {
            for (String kind : new String[] {"retry", "cancel"}) {
                for (boolean existing : new boolean[] {false, true}) {
                    for (int boundary = 0; boundary < BOUNDARIES.length; boundary++) {
                        Path dir = Files.createDirectory(root.resolve(kind + "-" + existing + "-" + boundary));
                        Path prefs = dir.resolve("preferences"), retry = dir.resolve("preferences.retry");
                        CancellationSignal signal = new CancellationSignal(prefs);
                        if (existing) signal.cancel();
                        String oldEpoch = signal.read();
                        // For cancellation, leave a retry bound to the old epoch: publication
                        // must invalidate it even if the owner never gets to remove the retry.
                        if (existing || kind.equals("cancel")) new PendingRetry(PARTITION, 1234, 2, true, oldEpoch).write(retry);
                        String before = readFresh(prefs);
                        killWriter(kind, prefs, boundary);
                        String after = readFresh(prefs);
                        if (boundary < 3) check(before.equals(after), "Unpublished write changed authority");
                        else if (kind.equals("retry")) check(after.equals(oldEpoch + "|3:" + oldEpoch), "Retry replacement not readable");
                        else {
                            String epoch = after.substring(0, after.indexOf('|'));
                            check(!epoch.equals(oldEpoch) && epoch.matches("[0-9a-f-]{36}"), "Cancellation not published");
                            check(after.equals(epoch + "|2:" + oldEpoch), "Old retry binding changed");
                            check(!PendingRetry.read(retry).cancellationEpoch.equals(signal.read()), "Stale retry revived");
                            checkControllerCancellation(prefs);
                        }
                        // A second crash at the same point must reuse, not accumulate,
                        // the abandoned per-record slot.
                        killWriter(kind, prefs, boundary);
                        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                            check(files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count() <= 1,
                                "Repeated process death accumulated temporary records");
                        }
                        // Fresh normal writers must recover without treating abandoned tmp files
                        // as authority. A subsequent valid retry binds to the latest cancellation.
                        signal.cancel();
                        new PendingRetry(PARTITION, 1234, 1, true, signal.read()).write(retry);
                        check(readFresh(prefs).equals(signal.read() + "|1:" + signal.read()), "Recovery failed");
                        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                            check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                                "Recovered writer left abandoned temporary records");
                        }
                        System.out.println("PASS " + kind + " " + (existing ? "replacement" : "first-write") + " death at " + BOUNDARIES[boundary]);
                    }
                }
            }
            for (int boundary = 0; boundary < 2; boundary++) {
                Path dir = Files.createDirectory(root.resolve("delete-" + boundary));
                Path prefs = dir.resolve("preferences"), retry = dir.resolve("preferences.retry");
                CancellationSignal signal = new CancellationSignal(prefs);
                signal.cancel();
                new PendingRetry(PARTITION, 1234, 2, true, signal.read()).write(retry);
                signal.cancel(); // Durable cancellation must invalidate any surviving retry.
                String before = readFresh(prefs);
                killAt(DeliveryController.class.getName(), prefs, "delete retry", boundary == 0
                    ? "if (Files.deleteIfExists(retryFile()))" : "directory.force(true);");
                String after = readFresh(prefs);
                if (boundary == 0) {
                    check(before.equals(after), "Retry disappeared before deletion");
                    checkControllerCancellation(prefs);
                } else check(after.equals(signal.read() + "|absent"), "Deleted retry revived after process death");
                System.out.println("PASS cancelled retry deletion death " + (boundary == 0 ? "before unlink" : "after unlink before directory sync"));
            }
            concurrentWriters(Files.createDirectory(root.resolve("concurrent")));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static void concurrentWriters(Path dir) throws Exception {
        Path prefs = dir.resolve("preferences"), retry = dir.resolve("preferences.retry");
        for (String kind : new String[] {"retry", "cancel"}) {
            List<Process> writers = new ArrayList<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 8; i++) writers.add(new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                    PersistenceDeathTest.class.getName(), "write", kind, prefs.toString()).inheritIO().start());
                List<java.util.concurrent.Future<?>> threads = new ArrayList<>();
                for (int i = 0; i < 2; i++) threads.add(pool.submit(() -> {
                    try {
                        for (int j = 0; j < 25; j++) {
                            if (kind.equals("retry")) new PendingRetry(PARTITION, 1234, 3, true).write(retry);
                            else new CancellationSignal(prefs).cancel();
                        }
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
                for (Process writer : writers)
                    check(writer.waitFor(15, TimeUnit.SECONDS) && writer.exitValue() == 0, "Concurrent process writer failed");
                for (java.util.concurrent.Future<?> thread : threads) thread.get(15, TimeUnit.SECONDS);
                check(PendingRetry.read(retry).retries == 3, "Concurrent retry not readable");
                if (kind.equals("cancel")) check(!new CancellationSignal(prefs).read().isEmpty(), "Cancellation missing");
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Concurrent writers left temporary records");
                }
                System.out.println("PASS " + kind + " concurrent writers: two local threads and eight processes");
            } finally {
                pool.shutdownNow();
                for (Process writer : writers) { writer.destroyForcibly(); writer.waitFor(10, TimeUnit.SECONDS); }
            }
        }
    }

    private static void killWriter(String kind, Path prefs, int boundary) throws Exception {
        String type = PendingRetry.class.getPackage().getName() + "." + (kind.equals("retry") ? "PendingRetry" : "CancellationSignal");
        String[] markers = {kind.equals("retry") ? "p.store(out," : "out.write(UUID", "out.getFD().sync();",
            "Files.move(tmp, path,", "directory.force(true);", "Files.deleteIfExists(tmp);"};
        killAt(type, prefs, "write " + kind, markers[boundary]);
    }

    private static void killAt(String type, Path prefs, String action, String marker) throws Exception {
        List<String> source = Files.readAllLines(Paths.get("src", type.replace('.', '/') + ".java"));
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) if (source.get(i).contains(marker)) lines.add(i + 1);
        check(lines.size() == 1, "Update debugger marker for " + type);
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("main").setValue(PersistenceDeathTest.class.getName() + " " + action + " " + prefs);
        options.get("options").setValue("-cp \"" + System.getProperty("java.class.path") + "\"");
        VirtualMachine vm = connector.launch(options);
        Process child = vm.process();
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(type); prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL); prepare.enable();
            vm.resume();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(100);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent) {
                        ReferenceType loaded = ((ClassPrepareEvent) event).referenceType();
                        List<Location> locations = loaded.locationsOfLine(lines.get(0));
                        check(!locations.isEmpty(), "Missing line information");
                        // finally can have separate normal/exceptional bytecode locations.
                        for (Location location : locations) {
                            BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(location);
                            stop.setSuspendPolicy(EventRequest.SUSPEND_ALL); stop.enable();
                        }
                    }
                    if (event instanceof BreakpointEvent) {
                        child.destroyForcibly();
                        check(child.waitFor(10, TimeUnit.SECONDS), "Writer did not die");
                        check(child.exitValue() != 0, "Writer completed instead of dying");
                        return;
                    }
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)
                        throw new AssertionError("Writer exited before boundary: " + type + " " + marker);
                }
                events.resume();
            }
            throw new AssertionError("Writer boundary timed out");
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
            try { vm.dispose(); } catch (VMDisconnectedException expected) { }
        }
    }

    private static String readFresh(Path prefs) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), PersistenceDeathTest.class.getName(), "read", "state", prefs.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            check(child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0, "Fresh reader failed");
            return new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } finally { child.destroyForcibly(); }
    }

    private static void checkControllerCancellation(Path prefs) throws Exception {
        try (DeliveryControllerTest.Control control = new DeliveryControllerTest.Control()) {
            // Feed the exact crash-surviving bytes into a new production controller.
            // Transport/lifecycle collaborators are host fakes, not installed evidence.
            for (String suffix : new String[] {".retry", ".cancel"})
                Files.copy(prefs.resolveSibling("preferences" + suffix),
                    control.preferences.resolveSibling("preferences" + suffix));
            control.controller.foreground(true);
            control.await(DeliveryController.Activity.CANCELLED); control.barrier();
            check(control.setup.f.requests == 0, "Cancelled retry sent HTTP after crash");
            check(!Files.exists(control.preferences.resolveSibling("preferences.retry")), "Stale retry not cleared");
            control.setup.head();
            control.setup.f.responses.add(new TransportTest.Fake(200, TransportTest.ARCHIVE));
            control.controller.checkNow();
            control.await(DeliveryController.Activity.READY);
            check(control.setup.f.requests == 2, "Explicit new attempt did not recover");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
