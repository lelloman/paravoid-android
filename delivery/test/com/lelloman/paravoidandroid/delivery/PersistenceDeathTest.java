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
                        // Fresh normal writers must recover without treating abandoned tmp files
                        // as authority. A subsequent valid retry binds to the latest cancellation.
                        signal.cancel();
                        new PendingRetry(PARTITION, 1234, 1, true, signal.read()).write(retry);
                        check(readFresh(prefs).equals(signal.read() + "|1:" + signal.read()), "Recovery failed");
                        System.out.println("PASS " + kind + " " + (existing ? "replacement" : "first-write") + " death at " + BOUNDARIES[boundary]);
                    }
                }
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static void killWriter(String kind, Path prefs, int boundary) throws Exception {
        String type = PendingRetry.class.getPackage().getName() + "." + (kind.equals("retry") ? "PendingRetry" : "CancellationSignal");
        String[] markers = {kind.equals("retry") ? "p.store(out," : "out.write(UUID", "out.getFD().sync();",
            "Files.move(tmp, path,", "directory.force(true);", "Files.deleteIfExists(tmp);"};
        List<String> source = Files.readAllLines(Paths.get("src", type.replace('.', '/') + ".java"));
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) if (source.get(i).contains(markers[boundary])) lines.add(i + 1);
        check(lines.size() == 1, "Update debugger marker for " + type);
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("main").setValue(PersistenceDeathTest.class.getName() + " write " + kind + " " + prefs);
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
                        throw new AssertionError("Writer exited before boundary: " + type + " " + BOUNDARIES[boundary]);
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
