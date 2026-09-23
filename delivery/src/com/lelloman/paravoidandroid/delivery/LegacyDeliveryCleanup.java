package com.lelloman.paravoidandroid.delivery;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/** Reclaims old process-partitioned transfer data after the shared-v1 migration. */
public final class LegacyDeliveryCleanup {
    private LegacyDeliveryCleanup() {}

    public static void reclaim(File noBackupDirectory, String packageName) throws IOException {
        if (!packageName.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("Invalid package");
        Path root = noBackupDirectory.toPath().resolve("paravoid-delivery");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path directory : children) {
                String name = directory.getFileName().toString();
                if (!(name.equals(packageName) || name.startsWith(packageName + "_"))
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                // A surviving old transfer retains its original inode and lock. Never
                // remove transfer.lock: the permanent channel may still be open here.
                try (DeliveryLocks.Claim claim = DeliveryLocks.tryAcquire(directory.resolve("transfer.lock"))) {
                    if (claim == null) continue;
                    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                        for (Path entry : entries) {
                            String file = entry.getFileName().toString();
                            boolean owned = file.matches("[0-9a-f]{64}\\.part")
                                    || file.equals("credential-scope") || file.equals("auth-denied")
                                    || file.matches("(?:credential-scope|auth-denied)[0-9]+\\.tmp");
                            if (owned && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                                Files.deleteIfExists(entry);
                        }
                    }
                }
            }
        }
    }
}
