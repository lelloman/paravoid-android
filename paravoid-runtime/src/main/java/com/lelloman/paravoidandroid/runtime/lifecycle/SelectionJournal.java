package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Private selection/trial authority. All methods run inside the common selection transaction. */
final class SelectionJournal {
    static final class Generation {
        final String directory;
        final ExpectedArchive identity;
        Generation(String directory, ExpectedArchive identity) {
            if (!directory.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid owned directory");
            this.directory = directory; this.identity = Objects.requireNonNull(identity);
        }
    }
    private static final class State {
        Generation selected, pending, healthy;
        boolean trial, quarantined;
        Code failure = Code.UNAVAILABLE;
        int incomplete, retained = 1;
        final List<Generation> history = new ArrayList<>();
        final Set<String> rejected = new LinkedHashSet<>();
    }
    private final Path root;
    private final AtomicRecord record;
    SelectionJournal(Path root) { this(root, boundary -> {}); }
    SelectionJournal(Path root, AtomicRecord.Fault fault) {
        this.root = root; record = new AtomicRecord(root.resolve("selection"), fault);
    }

    void initializeNew() throws IOException {
        ProcessLocks.requireSelection();
        if (Files.exists(root.resolve("selection"))) throw new IOException("Selection already initialized");
        record.write(encode(new State()));
    }
    static byte[] initialState() throws IOException { return encode(new State()); }
    private Path lease(Generation generation) { return root.resolve("lease-" + generation.directory); }
    private State read() throws ContractException {
        ProcessLocks.requireSelection();
        try { return decode(record.read()); }
        catch (IOException | RuntimeException e) { throw fail(Code.CORRUPT_STATE); }
    }
    private void write(State state) throws ContractException {
        try { record.write(encode(state)); }
        catch (IOException e) { throw fail(Code.IO); }
    }

    StageResult pending(Generation generation) throws ContractException {
        State s = read();
        boolean byteRepair = s.selected != null && s.selected.identity.equals(generation.identity)
            && s.quarantined && s.failure == Code.INTEGRITY;
        if (s.selected != null && s.selected.identity.equals(generation.identity) && !byteRepair)
            return new StageResult(StageStatus.ALREADY_SELECTED, generation.identity);
        if (s.pending != null && s.pending.identity.equals(generation.identity))
            return new StageResult(StageStatus.ALREADY_PENDING, generation.identity);
        // Defense in depth. Durable admission is responsible for lineage-wide floors.
        if (!byteRepair && s.selected != null && generation.identity.payloadVersion <= s.selected.identity.payloadVersion)
            throw fail(Code.REPLAY);
        s.pending = generation;
        write(s);
        return new StageResult(StageStatus.PENDING, generation.identity);
    }

    /** Snapshot a candidate, then verify owned bytes outside selection before acquire(). */
    Generation candidate() throws ContractException {
        State s = read();
        try {
            boolean cold = s.selected == null || ProcessLocks.ifUnleased(lease(s.selected), () -> null);
            if (cold && s.pending != null) return s.pending;
            if (s.selected == null || s.quarantined) throw fail(Code.UNAVAILABLE);
            return s.selected;
        } catch (IOException e) { throw fail(Code.IO); }
    }

    /** Pre-execution rejection cannot change selected code, even if an older healthy archive exists. */
    void rejectPending(Generation generation) throws ContractException {
        State s = read();
        if (s.pending != null && same(s.pending, generation)) {
            s.rejected.add(generation.directory); s.pending = null;
            while (s.rejected.size() > 64) s.rejected.remove(s.rejected.iterator().next());
            write(s);
        }
    }
    private static boolean same(Generation a, Generation b) {
        return a.directory.equals(b.directory) && a.identity.equals(b.identity);
    }

    /** Recheck the verified candidate, acquire its lease and mark trial before returning paths. */
    Generation acquire(Generation verified) throws ContractException {
        State s = read();
        try {
            boolean cold = s.selected == null || ProcessLocks.ifUnleased(lease(s.selected), () -> null);
            if (!cold) {
                if (s.quarantined || !same(s.selected, verified)) throw fail(Code.UNAVAILABLE);
                ProcessLocks.leaseForProcess(lease(s.selected));
                return s.selected;
            }
            Generation intended = s.pending != null ? s.pending : s.selected;
            if (intended == null || !same(intended, verified)) throw fail(Code.UNAVAILABLE);
            if (s.pending != null) {
                // No selected process remains, so the next higher candidate can replace even quarantine.
                Generation candidate = s.pending;
                if (!ProcessLocks.ifUnleased(lease(candidate), () -> null)) throw fail(Code.UNAVAILABLE);
                s.selected = candidate; s.pending = null; s.trial = true; s.quarantined = false; s.incomplete = 0;
            }
            if (s.selected == null || s.quarantined) throw fail(Code.UNAVAILABLE);
            if (s.trial && s.incomplete >= 2) {
                quarantine(s); write(s); throw fail(Code.UNAVAILABLE);
            }
            if (s.trial) s.incomplete++;
            write(s); // A kill after this write is an incomplete attempt; never evidence of successful entry.
            ProcessLocks.leaseForProcess(lease(s.selected));
            return s.selected;
        } catch (IOException e) { throw fail(Code.IO); }
    }

    void applicationCreated(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (s.quarantined) return; // A later success cannot undo another process's caught failure.
        s.incomplete = 0; write(s);
    }
    void healthy(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (s.quarantined) return;
        // Caller must establish main-process onCreate + resumed Activity first-frame ordering.
        if (s.healthy != null && !s.healthy.identity.equals(generation.identity)) s.history.add(0, s.healthy);
        s.healthy = generation; s.trial = false; s.incomplete = 0;
        while (s.history.size() > 3) s.history.remove(s.history.size() - 1);
        write(s);
    }
    void failed(Generation generation) throws ContractException {
        failed(generation, Code.UNAVAILABLE);
    }
    void failed(Generation generation, Code reason) throws ContractException {
        State s = forHandle(generation); quarantine(s); s.failure = reason; write(s);
    }
    void rejectedBytes(Generation generation) throws ContractException {
        State s = read();
        if (s.selected != null && same(s.selected, generation)) failed(generation, Code.INTEGRITY);
        else rejectPending(generation);
    }
    private static void quarantine(State s) {
        s.quarantined = true;
        s.rejected.add(s.selected.directory);
        while (s.rejected.size() > 64) s.rejected.remove(s.rejected.iterator().next());
    }
    private State forHandle(Generation generation) throws ContractException {
        State s = read();
        if (s.selected == null || !s.selected.directory.equals(generation.directory)
                || !s.selected.identity.equals(generation.identity)) throw fail(Code.UNAVAILABLE);
        return s;
    }
    void checkEntry(Generation generation) throws ContractException {
        if (forHandle(generation).quarantined) throw fail(Code.UNAVAILABLE);
    }
    Generation selectedForRetry(ExpectedArchive identity) throws ContractException {
        State s = read();
        if (!s.quarantined || s.pending != null || s.selected == null || !s.selected.identity.equals(identity)) throw fail(Code.UNAVAILABLE);
        return s.selected;
    }
    /** Facade must reverify intact bytes outside selection and obtain explicit user confirmation first. */
    void retry(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (!s.quarantined || s.pending != null) throw fail(Code.UNAVAILABLE);
        try {
            if (!ProcessLocks.ifUnleased(lease(generation), () -> null)) throw fail(Code.UNAVAILABLE);
        } catch (IOException e) { throw fail(Code.IO); }
        s.quarantined = false; s.trial = true; s.incomplete = 0;
        s.rejected.remove(generation.directory); write(s);
    }
    void retain(int count) throws ContractException {
        if (count < 0 || count > 3) throw fail(Code.MALFORMED);
        State s = read(); s.retained = count; write(s);
    }
    Set<String> protectedDirectories() throws ContractException {
        State s = read(); Set<String> result = new HashSet<>();
        if (s.selected != null) result.add(s.selected.directory);
        if (s.pending != null) result.add(s.pending.directory);
        // Last healthy may differ from selected during a trial: it is the first previous generation.
        int remaining = s.retained;
        if (remaining > 0 && s.healthy != null && (s.selected == null || !s.healthy.directory.equals(s.selected.directory))) {
            result.add(s.healthy.directory); remaining--;
        }
        for (Generation generation : s.history) if (remaining-- > 0) result.add(generation.directory);
        return result;
    }
    LifecycleSnapshot snapshot(long storageBytes) throws ContractException {
        State s = read(); boolean waiting = false;
        if (s.pending != null && s.selected != null) {
            try { waiting = !ProcessLocks.ifUnleased(lease(s.selected), () -> null); }
            catch (IOException e) { throw fail(Code.IO); }
        }
        Availability availability = s.quarantined ? Availability.RECOVERY : s.selected == null ? Availability.EMPTY
            : s.trial ? Availability.TRIAL : Availability.RUNNABLE;
        return new LifecycleSnapshot(availability, identity(s.selected), identity(s.pending), identity(s.healthy),
            waiting, s.retained, storageBytes, s.quarantined ? s.failure : null);
    }
    private static ExpectedArchive identity(Generation g) { return g == null ? null : g.identity; }
    private static ContractException fail(Code code) { return new ContractException(code, "Lifecycle selection: " + code.name()); }
    private static void generation(DataOutputStream out, Generation g) throws IOException {
        out.writeBoolean(g != null);
        if (g == null) return;
        out.writeUTF(g.directory); out.writeUTF(g.identity.releaseId); out.writeLong(g.identity.payloadVersion);
        out.writeUTF(g.identity.manifestSha256); out.writeUTF(g.identity.archiveSha256); out.writeLong(g.identity.archiveSize);
    }
    private static Generation generation(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        return new Generation(in.readUTF(), new ExpectedArchive(in.readUTF(), in.readLong(), in.readUTF(), in.readUTF(), in.readLong()));
    }
    private static byte[] encode(State s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2); generation(out, s.selected); generation(out, s.pending); generation(out, s.healthy);
        out.writeBoolean(s.trial); out.writeBoolean(s.quarantined); out.writeUTF(s.failure.name()); out.writeInt(s.incomplete); out.writeInt(s.retained);
        out.writeInt(s.history.size()); for (Generation generation : s.history) generation(out, generation);
        out.writeInt(s.rejected.size()); for (String rejected : s.rejected) out.writeUTF(rejected);
        return bytes.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); State s = new State();
        if (in.readInt() != 2) throw new IOException("Unknown selection version");
        s.selected = generation(in); s.pending = generation(in); s.healthy = generation(in);
        s.trial = in.readBoolean(); s.quarantined = in.readBoolean(); s.failure = Code.valueOf(in.readUTF()); s.incomplete = in.readInt(); s.retained = in.readInt();
        int history = in.readInt(); if (history < 0 || history > 3) throw new IOException("Invalid history count");
        for (int n = 0; n < history; n++) s.history.add(Objects.requireNonNull(generation(in)));
        int rejected = in.readInt(); if (rejected < 0 || rejected > 64) throw new IOException("Invalid rejected count");
        for (int n = 0; n < rejected; n++) if (!s.rejected.add(in.readUTF())) throw new IOException("Duplicate rejected generation");
        if (s.incomplete < 0 || s.incomplete > 2 || s.retained < 0 || s.retained > 3
                || (s.selected == null && (s.trial || s.quarantined)) || in.read() != -1)
            throw new IOException("Invalid selection state");
        return s;
    }
}
