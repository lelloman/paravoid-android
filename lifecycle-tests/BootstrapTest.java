package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BootstrapTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static Process child(String point, Path root) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
            BootstrapTest.class.getName(), point, root.toString()).inheritIO().start();
    }
    private static void fails(Path root) throws Exception {
        try { StoreBootstrap.open(root, "example.app", false, p -> {}); throw new AssertionError("Reset damaged state"); }
        catch (IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            StoreBootstrap.open(Paths.get(args[1]), "example.app", false, point -> {
                if (point.equals(args[0])) Runtime.getRuntime().halt(73);
            });
            return;
        }
        for (String point : Arrays.asList("anchor-started", "security-written", "selection-written", "root-published", "anchor-ready")) {
            Path root = Files.createTempDirectory("bootstrap-crash-").resolve("store");
            Process process = child(point, root);
            check(process.waitFor(10, TimeUnit.SECONDS)); check(process.exitValue() == 73);
            StoreBootstrap.open(root, "example.app", false, p -> {});
            byte[] before = new AtomicRecord(root.resolve("security")).read();
            StoreBootstrap.open(root, "example.app", false, p -> { throw new AssertionError("Repeated initialization"); });
            check(Arrays.equals(before, new AtomicRecord(root.resolve("security")).read()));
        }
        Path root = Files.createTempDirectory("bootstrap-race-").resolve("store");
        List<Process> racers = new ArrayList<>();
        for (int i = 0; i < 4; i++) racers.add(child("no-crash", root));
        for (Process process : racers) { check(process.waitFor(10, TimeUnit.SECONDS)); check(process.exitValue() == 0); }
        byte[] original = Files.readAllBytes(root.resolve("security"));
        Files.write(root.resolve("security"), new byte[]{1}); fails(root);
        check(Arrays.equals(new byte[]{1}, Files.readAllBytes(root.resolve("security"))));
        Files.write(root.resolve("security"), original);
        Files.move(root, root.resolveSibling("retained-store")); fails(root);
        check(!Files.exists(root));
        Path partial = Files.createTempDirectory("bootstrap-established-partial-").resolve("store");
        Files.createDirectory(partial); fails(partial);
        try (java.util.stream.Stream<Path> files = Files.list(partial)) { check(files.count() == 0); }
        System.out.println("PASS bootstrap: five process-death boundaries, four-process first-start race, no reset of corrupt/missing established history");
    }
}
