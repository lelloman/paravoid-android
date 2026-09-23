package com.lelloman.paravoidandroid.delivery;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Runs unchanged on host JVM and Android ART; no HTTP or component claims. */
public final class DeliveryLockProbe {
    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args[1]);
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (DeliveryLocks.Claim claim = DeliveryLocks.tryAcquire(path)) {
            if (args[0].equals("probe")) {
                System.out.println(claim == null ? "BUSY" : "FREE"); return;
            }
            if (claim == null) throw new AssertionError("initial claim busy");
            // Failed same-VM claims must not invalidate the first claim's OS lock.
            for (int i = 0; i < 10; i++) {
                try (DeliveryLocks.Claim peer = DeliveryLocks.tryAcquire(path.getParent().resolve(".").resolve(path.getFileName()))) {
                    if (peer != null) throw new AssertionError("same-VM exclusion failed");
                }
            }
            String pid = new String(Files.readAllBytes(Paths.get("/proc/self/stat")), StandardCharsets.US_ASCII).split(" ")[0];
            System.out.println("HELD " + pid); System.out.flush();
            for (int i = 0; i < 600; i++) {
                if (Files.exists(path.resolveSibling("release"))) return;
                Thread.sleep(100);
            }
            throw new AssertionError("owner handshake timed out");
        }
    }
}
