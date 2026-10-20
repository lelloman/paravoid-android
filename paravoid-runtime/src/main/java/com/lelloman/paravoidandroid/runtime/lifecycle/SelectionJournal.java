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
    /**
     * One cold trial start: this process, and what is known about how the previous unfinished
     * start ended. The previous evidence applies only to the exact attempt it was gathered for.
     */
    static final class Attempt {
        static final Attempt NONE = new Attempt(0, 0, 0, 0, null);
        final int pid, previousPid;
        final long millis, previousMillis;
        final StartupExits.Exit previous;
        Attempt(int pid, long millis, int previousPid, long previousMillis, StartupExits.Exit previous) {
            this.pid = Math.max(0, pid); this.millis = Math.max(0, millis);
            this.previousPid = previousPid; this.previousMillis = previousMillis; this.previous = previous;
        }
        StartupExits.Exit previousFor(int pid, long millis) {
            return previous != null && pid == previousPid && millis == previousMillis ? previous : StartupExits.Exit.UNKNOWN;
        }
    }
    /** Unfinished starts (crashed, hung, unknown end) allowed before a trial is paused in recovery. */
    static final int INCOMPLETE_LIMIT = 2;
    /** Backstop for starts killed from outside: evidence can be wrong, a start loop must still end. */
    static final int KILL_LIMIT = 8;
    static final String CAUSE_STARTUP_FAILURE = "STARTUP_FAILURE", CAUSE_STARTUP_INCOMPLETE = "STARTUP_INCOMPLETE",
        CAUSE_STARTUP_KILLED = "STARTUP_KILLED", CAUSE_INTEGRITY = "INTEGRITY", CAUSE_INCOMPATIBLE = "INCOMPATIBLE";
    private static final Set<String> CAUSES = new HashSet<>(Arrays.asList("", CAUSE_STARTUP_FAILURE,
        CAUSE_STARTUP_INCOMPLETE, CAUSE_STARTUP_KILLED, CAUSE_INTEGRITY, CAUSE_INCOMPATIBLE));
    private static final class State {
        Generation selected, pending, healthy;
        boolean trial, quarantined;
        Code failure = Code.UNAVAILABLE;
        int incomplete, retained = 1;
        // Version 3: the last unfinished trial start (pid 0: none or untracked), consecutive starts
        // killed from outside, how recent unfinished starts ended and why the selection is quarantined
        // ("" when unknown, e.g. quarantined by a version-2 runtime).
        int kills, attemptPid;
        long attemptMillis;
        String cause = "", exits = "";
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

    /** The unfinished trial start whose end the next cold start should classify, or null. */
    long[] previousAttempt() throws ContractException {
        State s = read();
        return s.trial && !s.quarantined && s.attemptPid > 0 ? new long[] {s.attemptPid, s.attemptMillis} : null;
    }

    Generation acquire(Generation verified) throws ContractException { return acquire(verified, Attempt.NONE); }
    /** Recheck the verified candidate, acquire its lease and mark trial before returning paths. */
    Generation acquire(Generation verified, Attempt attempt) throws ContractException {
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
                s.selected = candidate; s.pending = null; s.trial = true; s.quarantined = false;
                s.failure = Code.UNAVAILABLE; s.cause = ""; s.exits = ""; resetAttempts(s);
            }
            if (s.selected == null || s.quarantined) throw fail(Code.UNAVAILABLE);
            if (s.trial) {
                if (s.attemptPid > 0) {
                    // The previous start of this trial never finished Application.onCreate and its
                    // process is gone. A kill from outside is not evidence against the payload.
                    StartupExits.Exit exit = attempt.previousFor(s.attemptPid, s.attemptMillis);
                    noteExit(s, exit.label);
                    if (exit.kind == StartupExits.Kind.KILLED && s.incomplete > 0) { s.incomplete--; s.kills++; }
                    s.attemptPid = 0; s.attemptMillis = 0;
                }
                String exhausted = s.incomplete >= INCOMPLETE_LIMIT ? CAUSE_STARTUP_INCOMPLETE
                    : s.kills >= KILL_LIMIT ? CAUSE_STARTUP_KILLED : null;
                if (exhausted != null) { quarantine(s, exhausted, Code.UNAVAILABLE); write(s); throw fail(Code.UNAVAILABLE); }
                s.incomplete++;
                if (attempt.pid > 0) { s.attemptPid = attempt.pid; s.attemptMillis = attempt.millis; }
            }
            write(s); // A kill after this write is an incomplete attempt; never evidence of successful entry.
            ProcessLocks.leaseForProcess(lease(s.selected));
            return s.selected;
        } catch (IOException e) { throw fail(Code.IO); }
    }

    void applicationCreated(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (s.quarantined) return; // A later success cannot undo another process's caught failure.
        resetAttempts(s); write(s);
    }
    void healthy(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (s.quarantined) return;
        // Caller must establish main-process onCreate + resumed Activity first-frame ordering.
        if (s.healthy != null && !s.healthy.identity.equals(generation.identity)) s.history.add(0, s.healthy);
        s.healthy = generation; s.trial = false; resetAttempts(s); s.exits = "";
        while (s.history.size() > 3) s.history.remove(s.history.size() - 1);
        write(s);
    }
    void failed(Generation generation) throws ContractException {
        failed(generation, Code.UNAVAILABLE);
    }
    void failed(Generation generation, Code reason) throws ContractException {
        State s = forHandle(generation);
        // The first recorded cause stays (a later process may fail differently); corrupt bytes win
        // because they decide byte repair.
        boolean loading = reason == Code.INTEGRITY || reason == Code.INCOMPATIBLE;
        boolean keep = s.quarantined && !loading && !s.cause.isEmpty();
        quarantine(s, keep ? s.cause : reason == Code.INTEGRITY ? CAUSE_INTEGRITY
            : reason == Code.INCOMPATIBLE ? CAUSE_INCOMPATIBLE : CAUSE_STARTUP_FAILURE,
            keep ? s.failure : reason);
        write(s);
    }
    void rejectedBytes(Generation generation) throws ContractException { rejectedBytes(generation, Code.INTEGRITY); }
    /**
     * Load-time rejection. A selected generation that no longer fits the installed shell (INCOMPATIBLE,
     * e.g. after a contract-changing APK update) is paused with that cause; anything else is treated as
     * failed integrity, which permits a byte repair of the same release.
     */
    void rejectedBytes(Generation generation, Code code) throws ContractException {
        State s = read();
        if (s.selected != null && same(s.selected, generation))
            failed(generation, code == Code.INCOMPATIBLE ? Code.INCOMPATIBLE : Code.INTEGRITY);
        else rejectPending(generation);
    }
    private static void quarantine(State s, String cause, Code failure) {
        s.quarantined = true; s.cause = cause; s.failure = failure;
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
    /** A byte source only: does not acquire, trial-mark, retry or execute this generation. */
    Generation deltaBase(String archiveSha256) throws ContractException {
        State s = read();
        return s.selected != null && s.selected.identity.archiveSha256.equals(archiveSha256) ? s.selected : null;
    }
    ExpectedArchive deltaBaseIdentity() throws ContractException {
        State s = read(); return s.selected == null ? null : s.selected.identity;
    }
    /** Facade must reverify intact bytes outside selection and obtain explicit user confirmation first. */
    void retry(Generation generation) throws ContractException {
        State s = forHandle(generation);
        if (!s.quarantined || s.pending != null) throw fail(Code.UNAVAILABLE);
        try {
            if (!ProcessLocks.ifUnleased(lease(generation), () -> null)) throw fail(Code.UNAVAILABLE);
        } catch (IOException e) { throw fail(Code.IO); }
        s.quarantined = false; s.trial = true; s.failure = Code.UNAVAILABLE; s.cause = ""; s.exits = "";
        resetAttempts(s); s.rejected.remove(generation.directory); write(s);
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
    /** Plain-language reason for the current quarantine, or null. Bounded and credential-free. */
    String quarantineReport() throws ContractException {
        State s = read();
        if (!s.quarantined || s.selected == null) return null;
        String reason;
        switch (s.cause) {
            case CAUSE_STARTUP_FAILURE: reason = "it failed while starting (see the recorded startup failure)"; break;
            case CAUSE_STARTUP_INCOMPLETE: reason = (INCOMPLETE_LIMIT + 1) + " starts of its first run ended before the app"
                + " finished starting (a crash, a hang or an unknown exit)"; break;
            case CAUSE_STARTUP_KILLED: reason = KILL_LIMIT + " starts in a row of its first run were stopped before the app"
                + " finished starting"; break;
            case CAUSE_INTEGRITY: reason = "its stored files failed verification"; break;
            case CAUSE_INCOMPATIBLE: reason = "it does not fit the installed app version (built for another shell)"; break;
            default: reason = "an earlier app version paused it after its first starts failed (cause not recorded)";
        }
        return "Payload " + s.selected.identity.releaseId + " (version " + s.selected.identity.payloadVersion
            + ") is paused: " + reason + "." + (s.exits.isEmpty() ? "" : " Unfinished starts ended as: " + s.exits + ".");
    }
    private static void resetAttempts(State s) { s.incomplete = 0; s.kills = 0; s.attemptPid = 0; s.attemptMillis = 0; }
    private static void noteExit(State s, String label) {
        List<String> exits = new ArrayList<>(s.exits.isEmpty() ? Collections.emptyList() : Arrays.asList(s.exits.split(", ")));
        exits.add(StartupExits.Exit.label(label));
        while (exits.size() > 4) exits.remove(0);
        s.exits = String.join(", ", exits);
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
        out.writeInt(3); generation(out, s.selected); generation(out, s.pending); generation(out, s.healthy);
        out.writeBoolean(s.trial); out.writeBoolean(s.quarantined); out.writeUTF(s.failure.name()); out.writeInt(s.incomplete); out.writeInt(s.retained);
        out.writeInt(s.history.size()); for (Generation generation : s.history) generation(out, generation);
        out.writeInt(s.rejected.size()); for (String rejected : s.rejected) out.writeUTF(rejected);
        out.writeInt(s.kills); out.writeInt(s.attemptPid); out.writeLong(s.attemptMillis);
        out.writeUTF(s.cause); out.writeUTF(s.exits);
        return bytes.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); State s = new State();
        // Version 2 (shells before startup exit evidence) decodes with no attempt or cause recorded.
        int version = in.readInt();
        if (version != 2 && version != 3) throw new IOException("Unknown selection version");
        s.selected = generation(in); s.pending = generation(in); s.healthy = generation(in);
        s.trial = in.readBoolean(); s.quarantined = in.readBoolean(); s.failure = Code.valueOf(in.readUTF()); s.incomplete = in.readInt(); s.retained = in.readInt();
        int history = in.readInt(); if (history < 0 || history > 3) throw new IOException("Invalid history count");
        for (int n = 0; n < history; n++) s.history.add(Objects.requireNonNull(generation(in)));
        int rejected = in.readInt(); if (rejected < 0 || rejected > 64) throw new IOException("Invalid rejected count");
        for (int n = 0; n < rejected; n++) if (!s.rejected.add(in.readUTF())) throw new IOException("Duplicate rejected generation");
        if (version == 3) {
            s.kills = in.readInt(); s.attemptPid = in.readInt(); s.attemptMillis = in.readLong();
            s.cause = in.readUTF(); s.exits = in.readUTF();
        }
        if (s.kills < 0 || s.kills > KILL_LIMIT || s.attemptPid < 0 || s.attemptMillis < 0
                || !CAUSES.contains(s.cause) || s.exits.length() > 256) throw new IOException("Invalid trial state");
        if (s.incomplete < 0 || s.incomplete > INCOMPLETE_LIMIT || s.retained < 0 || s.retained > 3
                || (s.selected == null && (s.trial || s.quarantined)) || in.read() != -1)
            throw new IOException("Invalid selection state");
        return s;
    }
}
