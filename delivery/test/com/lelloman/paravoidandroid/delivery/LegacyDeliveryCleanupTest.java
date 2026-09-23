package com.lelloman.paravoidandroid.delivery;

import java.nio.file.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

public final class LegacyDeliveryCleanupTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("legacy-delivery-");
        String app = "example.complete.paravoid";
        Path parent = root.resolve("paravoid-delivery");
        Path oldMain = Files.createDirectories(parent.resolve(app));
        Path oldRecovery = Files.createDirectories(parent.resolve(app + "_paravoid_recovery"));
        Path shared = Files.createDirectories(parent.resolve("shared-v1"));
        Path unrelated = Files.createDirectories(parent.resolve("other.package"));
        Path part = oldMain.resolve("a".repeat(64) + ".part");
        Path orphan = oldMain.resolve("credential-scope123.tmp");
        Path unknown = oldMain.resolve("unknown");
        Files.writeString(part, "old archive"); Files.writeString(orphan, "old marker");
        Files.writeString(unknown, "leave alone");
        Files.writeString(oldRecovery.resolve("auth-denied"), "old denial");
        Files.writeString(shared.resolve("b".repeat(64) + ".part"), "live shared data");
        Files.writeString(unrelated.resolve("c".repeat(64) + ".part"), "other data");
        Path busy = oldRecovery.resolve("d".repeat(64) + ".part");
        Files.writeString(busy, "busy");
        try (DeliveryLocks.Claim held = DeliveryLocks.tryAcquire(oldRecovery.resolve("transfer.lock"))) {
            check(held != null);
            LegacyDeliveryCleanup.reclaim(root.toFile(), app);
            check(!Files.exists(part) && !Files.exists(orphan));
            check(Files.exists(unknown) && Files.exists(busy));
            check(Files.exists(shared.resolve("b".repeat(64) + ".part")));
            check(Files.exists(unrelated.resolve("c".repeat(64) + ".part")));
        }
        LegacyDeliveryCleanup.reclaim(root.toFile(), app);
        check(!Files.exists(busy) && !Files.exists(oldRecovery.resolve("auth-denied")));
        check(Files.exists(oldMain.resolve("transfer.lock")) && Files.exists(oldRecovery.resolve("transfer.lock")));
        System.out.println("PASS legacy delivery: scoped partial cleanup, busy-owner preservation, shared and unknown file safety");
    }
}
