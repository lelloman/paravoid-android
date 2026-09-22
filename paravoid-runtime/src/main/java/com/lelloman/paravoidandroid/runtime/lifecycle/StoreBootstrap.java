package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** First-install transaction. The parent anchor is never used to repair/reset established state. */
final class StoreBootstrap {
    private StoreBootstrap() {}
    static void open(Path root, String applicationId, boolean requireNew, AtomicRecord.Fault fault) throws IOException {
        Path parent = root.getParent();
        String prefix = "." + root.getFileName() + ".bootstrap";
        ProcessLocks.preparation(parent.resolve(prefix + ".lock"), () -> {
            AtomicRecord anchor = new AtomicRecord(parent.resolve(prefix + ".state"));
            Path pending = parent.resolve(prefix + ".pending");
            byte[] status = Files.exists(parent.resolve(prefix + ".state"), LinkOption.NOFOLLOW_LINKS) ? anchor.read() : null;
            if (status != null && (!Arrays.equals(status, new byte[]{1}) && !Arrays.equals(status, new byte[]{2})))
                throw new IOException("Invalid bootstrap anchor");
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (requireNew) throw new IOException("Store already exists");
                validate(root);
                if (status == null || status[0] == 1) anchor.write(new byte[]{2});
                return null;
            }
            if (status != null && status[0] == 2) throw new IOException("Established store missing");
            if (status == null) {
                if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unowned bootstrap directory");
                anchor.write(new byte[]{1});
            }
            fault.at("anchor-started");
            discardUnpublished(pending);
            Files.createDirectory(pending);
            new AtomicRecord(pending.resolve("security")).write(AdmissionStore.initialState(applicationId));
            fault.at("security-written");
            new AtomicRecord(pending.resolve("selection")).write(SelectionJournal.initialState());
            fault.at("selection-written");
            for (String name : Arrays.asList("staging", "generations", "trash")) {
                Files.createDirectory(pending.resolve(name)); AtomicRecord.syncDirectory(pending.resolve(name));
            }
            AtomicRecord.syncDirectory(pending);
            // All cooperating starters hold the permanent parent lock. No lock descriptor is moved.
            Files.move(pending, root, StandardCopyOption.ATOMIC_MOVE);
            AtomicRecord.syncDirectory(parent);
            fault.at("root-published");
            anchor.write(new byte[]{2});
            fault.at("anchor-ready");
            return null;
        });
    }
    private static void validate(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid store directory");
        for (String name : Arrays.asList("security", "selection")) {
            if (!Files.isRegularFile(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing store record");
            new AtomicRecord(root.resolve(name)).read();
        }
        for (String name : Arrays.asList("staging", "generations", "trash"))
            if (!Files.isDirectory(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing store directory");
    }
    private static void discardUnpublished(Path pending) throws IOException {
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid bootstrap directory");
        try (java.util.stream.Stream<Path> stream = Files.list(pending)) {
            List<Path> paths = stream.collect(java.util.stream.Collectors.toList());
            for (Path path : paths) {
                String name = path.getFileName().toString();
                boolean directory = Arrays.asList("staging", "generations", "trash").contains(name);
                if (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        : !(name.equals("security") || name.equals("selection") || name.matches("\\.record-[A-Za-z0-9_-]+\\.tmp"))
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected unpublished bootstrap entry");
            }
            // These directories can only be empty: publication precedes any payload work.
            for (Path path : paths) Files.delete(path);
        }
        Files.delete(pending);
        AtomicRecord.syncDirectory(pending.getParent());
    }
}
