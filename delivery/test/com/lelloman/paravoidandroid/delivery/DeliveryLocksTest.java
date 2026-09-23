package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class DeliveryLocksTest {
    private static Process start(String mode, Path path) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), DeliveryLockProbe.class.getName(), mode, path.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static void probe(Path path, String expected) throws Exception {
        Process process = start("probe", path);
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0
                || !new String(process.getInputStream().readAllBytes()).trim().equals(expected))
                throw new AssertionError("expected " + expected);
        } finally { process.destroyForcibly(); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("delivery-locks-");
        for (String name : new String[] {"preferences.attempt", "transfer.lock"}) {
            Path path = root.resolve(name);
            for (boolean kill : new boolean[] {false, true}) {
                Files.deleteIfExists(root.resolve("release"));
                Process owner = start("hold", path);
                ExecutorService reader = Executors.newSingleThreadExecutor();
                try {
                    String ready = reader.submit(() -> new BufferedReader(new InputStreamReader(owner.getInputStream())).readLine())
                        .get(10, TimeUnit.SECONDS);
                    if (ready == null || !ready.startsWith("HELD ")) throw new AssertionError("owner not ready");
                    probe(path, "BUSY");
                    if (kill) owner.destroyForcibly(); else Files.createFile(root.resolve("release"));
                    if (!owner.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("owner did not exit");
                    if (!kill && owner.exitValue() != 0) throw new AssertionError("owner failed");
                    probe(path, "FREE");
                } finally { owner.destroyForcibly(); reader.shutdownNow(); }
            }
        }
        System.out.println("PASS delivery locks: same-VM contention preserves cross-process exclusion; close and death release both locks");
    }
}
