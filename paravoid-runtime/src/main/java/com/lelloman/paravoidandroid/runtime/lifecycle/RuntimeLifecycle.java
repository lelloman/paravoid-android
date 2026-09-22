package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Disk-only v1 lifecycle. Caller supplies installed policy, process identity and mandatory authentication. */
public final class RuntimeLifecycle implements Lifecycle {
    /** Android adapter must use boot-wide elapsed realtime, not per-process uptime or wall time as boot identity. */
    public interface Clock {
        long unixSeconds(); long elapsedSeconds(); String bootId();
    }
    private static final Map<Path, Lease> PROCESS_HANDLES = new HashMap<>();
    private final Path root;
    private final AdmissionStore admission;
    private final GenerationStore generations;
    private final boolean mainProcess;
    private final ShellPolicy policy;
    private final InstalledStateSource installed;

    /** Opens established state. Missing/damaged state never triggers initialization. Use Android no-backup storage. */
    public RuntimeLifecycle(File root, ShellPolicy policy, RequestScope device, VpkVerifier verifier,
            Clock clock, boolean mainProcess) throws ContractException {
        this(root, policy, device, verifier, clock, mainProcess, null);
    }
    public RuntimeLifecycle(File root, ShellPolicy policy, RequestScope device, VpkVerifier verifier,
            Clock clock, boolean mainProcess, InstalledStateSource installed) throws ContractException {
        this.root = root.toPath().toAbsolutePath().normalize(); this.mainProcess = mainProcess;
        this.policy = policy; this.installed = installed;
        if (installed == null && policy.contractDescriptor().length != 0) {
            Object descriptor = StrictJson.parse(policy.contractDescriptor(), InstalledPolicyCodec.MAX_BYTES);
            if (descriptor instanceof Map && InstalledPolicyCodec.PROFILE.equals(((Map<?,?>)descriptor).get("profile")))
                throw new ContractException(Code.INCOMPATIBLE, "Complete APK lifecycle requires installed-state authority");
        }
        admission = new AdmissionStore(this.root, policy, new AdmissionStore.Clock() {
            public long unixSeconds() { return clock.unixSeconds(); }
            public long elapsedSeconds() { return clock.elapsedSeconds(); }
            public String bootId() { return clock.bootId(); }
        }, installed);
        generations = new GenerationStore(this.root, policy, device, verifier);
    }

    /** Explicit first-install operation; fails if any store directory already exists, even partially. */
    public void initializeNew() throws ContractException {
        admission.initializeNew();
        try {
            Files.createDirectory(root.resolve("staging")); Files.createDirectory(root.resolve("generations"));
            Files.createDirectory(root.resolve("trash")); AtomicRecord.syncDirectory(root);
            selection(j -> { j.initializeNew(); return null; });
        } catch (IOException e) { throw fail(Code.IO); }
    }
    @Override public void setCredentialScope(CredentialScope scope) throws ContractException { admission.setCredentialScope(scope); }
    @Override public AdmissionResult observeHead(VerifiedHead head, CredentialScope scope) throws ContractException {
        return admission.observeHead(head, scope);
    }
    @Override public StageResult stageDownloaded(File archive, AdmissionId id) throws ContractException {
        ExpectedArchive expected = admission.resolve(id);
        cleanup();
        return preparation(() -> {
            cleanAbandonedStaging();
            GenerationStore.Prepared prepared = generations.prepare(archive, expected);
            return admission.authorizePublication(id, current -> {
                if (!expected.equals(current)) throw fail(Code.STALE_ADMISSION);
                SelectionJournal.Generation published = generations.publish(prepared);
                return new SelectionJournal(root).pending(published);
            });
        });
    }
    @Override public StageResult stageEmbedded(File archive) throws ContractException {
        cleanup();
        return preparation(() -> {
            cleanAbandonedStaging();
            GenerationStore.Prepared prepared = generations.prepare(archive, null);
            // Admission commits first. A publication interruption preserves floors and supports identical retry.
            return admission.authorizeEmbedded(prepared.release,
                identity -> new SelectionJournal(root).pending(generations.publish(prepared)));
        });
    }
    @Override public LifecycleSnapshot snapshot() throws ContractException {
        long bytes = storageBytes(); return selection(j -> j.snapshot(bytes));
    }
    @Override public GenerationLease acquireForProcess() throws ContractException {
        InstalledStateSource.Snapshot current = currentInstalled();
        synchronized (PROCESS_HANDLES) {
            Lease existing = PROCESS_HANDLES.get(root);
            if (existing != null) {
                if (!policy.shellContractId.equals(existing.release().shellContractId)) throw fail(Code.INCOMPATIBLE);
                existing.beforeUserCode(); return existing;
            }
            // Staging may supersede a candidate while its bytes are verified. Bound local retries, never load stale paths.
            for (int attempt = 0; attempt < 3; attempt++) {
                SelectionJournal.Generation candidate = selection(SelectionJournal::candidate);
                GenerationStore.Loaded loaded;
                try { loaded = generations.load(candidate); }
                catch (ContractException rejected) {
                    selection(j -> {
                        j.rejectedBytes(candidate);
                        return null;
                    });
                    throw rejected;
                }
                try {
                    SelectionJournal.Generation acquired = selection(j -> {
                        if (current != null && !installed.isCurrent(current)) throw fail(Code.CREDENTIAL_CHANGED);
                        return j.acquire(candidate);
                    });
                    Lease handle = new Lease(acquired, loaded); PROCESS_HANDLES.put(root, handle); return handle;
                } catch (ContractException changed) {
                    if (changed.code != Code.UNAVAILABLE) throw changed;
                }
            }
            throw fail(Code.UNAVAILABLE);
        }
    }
    @Override public void retryQuarantined(ExpectedArchive release) throws ContractException {
        InstalledStateSource.Snapshot current = currentInstalled();
        SelectionJournal.Generation candidate = selection(j -> j.selectedForRetry(release));
        generations.load(candidate); // An intact retry is explicit; corruption must be repaired with verified bytes.
        selection(j -> {
            if (current != null && !installed.isCurrent(current)) throw fail(Code.CREDENTIAL_CHANGED);
            j.retry(candidate); return null;
        });
    }
    @Override public void setRetainedPrevious(int count) throws ContractException {
        selection(j -> { j.retain(count); return null; });
        cleanup();
    }

    /** Optional-history cleanup, with atomic removal from the loading namespace before slow deletion. */
    public void cleanup() throws ContractException {
        preparation(() -> {
            cleanAbandonedStaging();
            List<Path> candidates;
            try (java.util.stream.Stream<Path> entries = Files.list(root.resolve("generations"))) {
                candidates = entries.collect(java.util.stream.Collectors.toList());
            }
            for (Path candidate : candidates) selection(j -> {
                String name = candidate.getFileName().toString();
                if (!name.matches("[A-Za-z0-9_-]{1,128}") || j.protectedDirectories().contains(name)) return null;
                ProcessLocks.ifUnleased(root.resolve("lease-" + name), () -> {
                    Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rwx------"));
                    Files.move(candidate, root.resolve("trash").resolve(name), StandardCopyOption.ATOMIC_MOVE);
                    AtomicRecord.syncDirectory(root.resolve("generations")); AtomicRecord.syncDirectory(root.resolve("trash"));
                    return null;
                });
                return null;
            });
            deleteChildren(root.resolve("trash")); return null;
        });
    }
    private void cleanAbandonedStaging() throws IOException { deleteChildren(root.resolve("staging")); }
    private static void deleteChildren(Path directory) throws IOException {
        // Preparation OS lock proves no writer remains. No PID, age or timeout inference.
        try (java.util.stream.Stream<Path> children = Files.list(directory)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                try (java.util.stream.Stream<Path> tree = Files.walk(child)) {
                    List<Path> entries = tree.collect(java.util.stream.Collectors.toList());
                    for (Path path : entries) if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                    Collections.reverse(entries);
                    for (Path path : entries) Files.delete(path);
                }
            }
        }
        AtomicRecord.syncDirectory(directory);
    }
    private long storageBytes() throws ContractException {
        try (java.util.stream.Stream<Path> tree = Files.walk(root)) {
            long total = 0;
            for (Path path : (Iterable<Path>) tree::iterator) {
                try { if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(path); }
                catch (NoSuchFileException concurrentCleanup) { /* Snapshot only, never an admission decision. */ }
            }
            return total;
        } catch (IOException | UncheckedIOException e) { throw fail(Code.IO); }
    }
    private interface Work<T> { T run() throws ContractException, IOException; }
    private interface JournalWork<T> { T run(SelectionJournal journal) throws ContractException, IOException; }
    private <T> T selection(JournalWork<T> work) throws ContractException {
        return lock(false, () -> work.run(new SelectionJournal(root)));
    }
    private <T> T preparation(Work<T> work) throws ContractException { return lock(true, work); }
    private <T> T lock(boolean preparation, Work<T> work) throws ContractException {
        ContractException[] problem = new ContractException[1];
        ProcessLocks.Operation<T> operation = () -> {
            try { return work.run(); } catch (ContractException e) { problem[0] = e; return null; }
        };
        try {
            T value = preparation ? ProcessLocks.preparation(root.resolve("prepare.lock"), operation)
                : ProcessLocks.selection(root.resolve("selection.lock"), operation);
            if (problem[0] != null) throw problem[0];
            return value;
        } catch (IOException e) { throw fail(Code.IO); }
    }
    private final class Lease implements GenerationLease {
        private final SelectionJournal.Generation generation;
        private final GenerationStore.Loaded loaded;
        private boolean applicationCreated;
        Lease(SelectionJournal.Generation generation, GenerationStore.Loaded loaded) { this.generation = generation; this.loaded = loaded; }
        public VerifiedRelease release() { return loaded.release; }
        public GenerationFiles files() { return loaded.files; }
        public void beforeUserCode() throws ContractException {
            InstalledStateSource.Snapshot current = currentInstalled();
            selection(j -> {
                if (current != null && !installed.isCurrent(current)) throw fail(Code.CREDENTIAL_CHANGED);
                j.checkEntry(generation); return null;
            }); // Trial already durable before paths were returned.
        }
        public synchronized void applicationCreated() throws ContractException {
            selection(j -> { j.applicationCreated(generation); return null; }); applicationCreated = true;
        }
        public synchronized void firstFrameRendered() throws ContractException {
            if (!mainProcess || !applicationCreated) throw fail(Code.UNAVAILABLE);
            selection(j -> { j.healthy(generation); return null; });
        }
        public void startupFailed(Code safeReason) throws ContractException {
            selection(j -> { j.failed(generation, Objects.requireNonNull(safeReason)); return null; });
        }
    }
    private InstalledStateSource.Snapshot currentInstalled() throws ContractException {
        if (installed == null) return null;
        InstalledStateSource.Snapshot current = installed.read();
        if (current == null || !policy.applicationId.equals(current.policy.applicationId)
                || !policy.shellContractId.equals(current.policy.shellContractId)) throw fail(Code.INCOMPATIBLE);
        return current;
    }
    private static ContractException fail(Code code) { return new ContractException(code, "Runtime lifecycle: " + code.name()); }
}
