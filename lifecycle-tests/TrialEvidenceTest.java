package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trial starts that end before Application.onCreate finished, classified by platform exit evidence,
 * and the payload-72 incident: embedded 65, downloaded 72, starts killed during its trial. Every
 * cold start is a separate JVM, so generation leases are real OS locks released by process exit.
 * Exit evidence is injected (Android reads ApplicationExitInfo); fixture archives are not signed VPKs.
 */
public final class TrialEvidenceTest {
    static final AtomicLong FREE = new AtomicLong(Long.MAX_VALUE);

    static RuntimeLifecycle embedded(Path root, LifecycleTest.Fixture fixture, StartupExits exits) throws Exception {
        ShellPolicy base = AdmissionTest.policy(Authentication.PUBLIC, "contract");
        ShellPolicy policy = new ShellPolicy("app", "contract", base.trust, "https://test/", "stable", Authentication.PUBLIC,
            Bootstrap.EMBEDDED, true, false, 1, java.util.Collections.emptyMap(), new byte[0]);
        return new RuntimeLifecycle(root.toFile(), policy, AdmissionTest.scope("contract", 30), fixture,
            LifecycleTest.CLOCK, true, null, exits);
    }
    /** Evidence that the previous unfinished start ended as {@code previous}. */
    static StartupExits evidence(StartupExits.Kind previous) {
        return new StartupExits() {
            public int currentPid() { return (int) ProcessHandle.current().pid(); }
            public long currentMillis() { return System.currentTimeMillis(); }
            public Exit exit(int pid, long startedMillis) {
                return previous == StartupExits.Kind.UNKNOWN ? Exit.UNKNOWN : new Exit(previous, "test " + previous.name().toLowerCase());
            }
        };
    }

    /** One cold start in a child JVM: "killed" never reaches onCreate, "created" does, "healthy" also draws. */
    static String start(Path parent, Path root, int version, String mode, StartupExits.Kind previous) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), TrialEvidenceTest.class.getName(), "child", parent.toString(),
            root.toString(), Integer.toString(version), mode, previous.name())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line = new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();
        AdmissionTest.check(child.waitFor(20, TimeUnit.SECONDS) && child.exitValue() == 0);
        return line;
    }
    static void child(String[] args) throws Exception {
        Path parent = Paths.get(args[1]), root = Paths.get(args[2]);
        LifecycleTest.Fixture fixture = new LifecycleTest.Fixture(parent, Integer.parseInt(args[3]), true);
        RuntimeLifecycle lifecycle = embedded(root, fixture, evidence(StartupExits.Kind.valueOf(args[5])));
        try {
            GenerationLease lease = lifecycle.acquireForProcess();
            if (!args[4].equals("killed")) lease.applicationCreated();
            if (args[4].equals("healthy")) lease.firstFrameRendered();
            System.out.println("OK " + lease.release().identity.payloadVersion);
        } catch (ContractException failure) { System.out.println(failure.code.name()); }
        System.out.flush();
    }

    static final class Install {
        final Path parent, root;
        final LifecycleTest.Fixture embedded65, downloaded72;
        final RuntimeLifecycle shell, updates;
        /** Embedded 65 runs and is healthy; 72 is admitted (floor 72) and staged pending. */
        Install(String name) throws Exception {
            parent = Files.createTempDirectory(name); root = parent.resolve("store");
            embedded65 = new LifecycleTest.Fixture(parent, 65); downloaded72 = new LifecycleTest.Fixture(parent, 72);
            shell = embedded(root, embedded65, StartupExits.NONE); shell.initializeNew();
            AdmissionTest.check(shell.stageEmbedded(embedded65.source).status == StageStatus.PENDING);
            AdmissionTest.check(TrialEvidenceTest.start(parent, root, 65, "healthy", StartupExits.Kind.UNKNOWN).equals("OK 65"));
            updates = embedded(root, downloaded72, StartupExits.NONE);
            AdmissionTest.check(updates.stageDownloaded(downloaded72.source, LifecycleTest.admit(updates, downloaded72)).status == StageStatus.PENDING);
        }
        String start(String mode, StartupExits.Kind previous) throws Exception { return TrialEvidenceTest.start(parent, root, 72, mode, previous); }
        byte[] selection() throws IOException { return Files.readAllBytes(root.resolve("selection")); }
        byte[] security() throws IOException { return Files.readAllBytes(root.resolve("security")); }
        /** The embedded payload is below the floor: refused without touching selection or replay state. */
        void embeddedRefused() throws Exception {
            byte[] selection = selection(), security = security();
            AdmissionTest.fails(Code.REPLAY, () -> shell.stageEmbedded(embedded65.source));
            AdmissionTest.check(java.util.Arrays.equals(selection, selection()) && java.util.Arrays.equals(security, security()));
        }
    }

    /** The incident: two (and more) killed trial starts keep 72; it runs and becomes healthy. No REPLAY. */
    static void killedStartsKeepThePayload() throws Exception {
        Install install = new Install("trial-killed-");
        AdmissionTest.check(install.start("killed", StartupExits.Kind.UNKNOWN).equals("OK 72"));
        AdmissionTest.check(install.start("killed", StartupExits.Kind.KILLED).equals("OK 72"));
        AdmissionTest.check(install.start("killed", StartupExits.Kind.KILLED).equals("OK 72")); // Was: silent quarantine.
        LifecycleSnapshot trial = install.updates.snapshot();
        AdmissionTest.check(trial.availability == Availability.TRIAL && trial.active.payloadVersion == 72
            && trial.lastHealthy.payloadVersion == 65 && install.updates.quarantineReport() == null);
        install.embeddedRefused(); // Never staged below the floor, and nothing needs it to be.
        AdmissionTest.check(install.start("healthy", StartupExits.Kind.KILLED).equals("OK 72"));
        LifecycleSnapshot healthy = install.updates.snapshot();
        AdmissionTest.check(healthy.availability == Availability.RUNNABLE && healthy.active.payloadVersion == 72
            && healthy.lastHealthy.payloadVersion == 72);
    }

    /** Without kill evidence the budget is as before, but the quarantine is reported and REPLAY never replaces it. */
    static void unexplainedStartsQuarantineWithReason() throws Exception {
        Install install = new Install("trial-unknown-");
        AdmissionTest.check(install.start("killed", StartupExits.Kind.UNKNOWN).equals("OK 72"));
        AdmissionTest.check(install.start("killed", StartupExits.Kind.FAILURE).equals("OK 72"));
        AdmissionTest.check(install.start("killed", StartupExits.Kind.UNKNOWN).equals("UNAVAILABLE"));
        LifecycleSnapshot paused = install.updates.snapshot();
        AdmissionTest.check(paused.availability == Availability.RECOVERY && paused.active.payloadVersion == 72
            && paused.pending == null && paused.lastHealthy.payloadVersion == 65 && paused.error == Code.UNAVAILABLE);
        String report = install.updates.quarantineReport();
        AdmissionTest.check(report.contains("release-72") && report.contains("3 starts")
            && report.contains("test failure, unknown") && !report.contains("REPLAY"));
        // The retained healthy 65 is below the floor: never selected, never re-staged.
        install.embeddedRefused();
        AdmissionTest.check(install.start("healthy", StartupExits.Kind.KILLED).equals("UNAVAILABLE"));
        AdmissionTest.check(install.updates.snapshot().availability == Availability.RECOVERY
            && report.equals(install.updates.quarantineReport()));
        // Explicit retry runs 72 again; it starts and becomes healthy.
        install.updates.retryQuarantined(paused.active);
        AdmissionTest.check(install.updates.quarantineReport() == null);
        AdmissionTest.check(install.start("healthy", StartupExits.Kind.UNKNOWN).equals("OK 72"));
        AdmissionTest.check(install.updates.snapshot().availability == Availability.RUNNABLE);
    }

    /** Kills are bounded too (evidence can be wrong); a finished onCreate resets every count. */
    static void killBackstopAndReset() throws Exception {
        Install install = new Install("trial-backstop-");
        AdmissionTest.check(install.start("killed", StartupExits.Kind.UNKNOWN).equals("OK 72"));
        for (int i = 0; i < 5; i++) AdmissionTest.check(install.start("killed", StartupExits.Kind.KILLED).equals("OK 72"));
        AdmissionTest.check(install.start("created", StartupExits.Kind.KILLED).equals("OK 72")); // Progress: counts reset.
        AdmissionTest.check(install.updates.snapshot().availability == Availability.TRIAL);
        for (int i = 0; i < SelectionJournal.KILL_LIMIT; i++)
            AdmissionTest.check(install.start("killed", i == 0 ? StartupExits.Kind.UNKNOWN : StartupExits.Kind.KILLED).equals("OK 72"));
        AdmissionTest.check(install.start("killed", StartupExits.Kind.KILLED).equals("UNAVAILABLE"));
        String report = install.updates.quarantineReport();
        AdmissionTest.check(report.contains(SelectionJournal.KILL_LIMIT + " starts in a row") && report.contains("test killed"));
        install.embeddedRefused();
    }

    /** Evidence gathered for another attempt never excuses this one; integrity and version-2 causes are reported. */
    static void journalEvidenceIntegrityAndUpgrade() throws Exception {
        Path root = Files.createTempDirectory("trial-journal-");
        SelectionTest.locked(root, j -> { j.initializeNew(); return null; });
        SelectionJournal.Generation one = SelectionTest.generation(1), two = SelectionTest.generation(2);
        SelectionTest.locked(root, j -> j.pending(one));
        StartupExits.Exit killed = new StartupExits.Exit(StartupExits.Kind.KILLED, "killed");
        AdmissionTest.check(SelectionTest.locked(root, j -> j.previousAttempt()) == null);
        // First start of the trial records its attempt; the next start's evidence names another pid.
        AdmissionTest.check(journalChild(root, "first").equals("OK"));
        long[] previous = SelectionTest.locked(root, j -> j.previousAttempt());
        AdmissionTest.check(previous != null && previous[0] == 4242 && previous[1] == 1000);
        AdmissionTest.check(new SelectionJournal.Attempt(1, 2, 4241, 1000, killed).previousFor(4242, 1000) == StartupExits.Exit.UNKNOWN);
        AdmissionTest.check(new SelectionJournal.Attempt(1, 2, 4242, 999, killed).previousFor(4242, 1000) == StartupExits.Exit.UNKNOWN);
        AdmissionTest.check(new SelectionJournal.Attempt(1, 2, 4242, 1000, killed).previousFor(4242, 1000) == killed);
        AdmissionTest.check(journalChild(root, "mismatch").equals("OK"));
        AdmissionTest.check(journalChild(root, "mismatch").equals("UNAVAILABLE"));
        AdmissionTest.check(SelectionTest.locked(root, j -> j.snapshot(0)).availability == Availability.RECOVERY);
        AdmissionTest.check(SelectionTest.locked(root, j -> j.quarantineReport()).contains("unknown, unknown"));

        Path integrity = Files.createTempDirectory("trial-integrity-");
        SelectionTest.locked(integrity, j -> { j.initializeNew(); return null; });
        SelectionTest.locked(integrity, j -> j.pending(two));
        SelectionJournal.Generation acquired = SelectionTest.locked(integrity, j -> j.acquire(two));
        SelectionTest.locked(integrity, j -> { j.rejectedBytes(acquired); j.failed(acquired, Code.UNAVAILABLE); return null; });
        LifecycleSnapshot corrupt = SelectionTest.locked(integrity, j -> j.snapshot(0));
        AdmissionTest.check(corrupt.availability == Availability.RECOVERY && corrupt.error == Code.INTEGRITY); // Byte repair stays possible.
        AdmissionTest.check(SelectionTest.locked(integrity, j -> j.quarantineReport()).contains("failed verification"));

        // A version-2 journal (quarantined by an older runtime) still opens and says the cause is unknown.
        Path legacy = Files.createTempDirectory("trial-legacy-");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2);
        out.writeBoolean(true); out.writeUTF(two.directory); out.writeUTF(two.identity.releaseId); out.writeLong(2);
        out.writeUTF(two.identity.manifestSha256); out.writeUTF(two.identity.archiveSha256); out.writeLong(two.identity.archiveSize);
        out.writeBoolean(false); out.writeBoolean(false);
        out.writeBoolean(true); out.writeBoolean(true); out.writeUTF("UNAVAILABLE"); out.writeInt(2); out.writeInt(1);
        out.writeInt(0); out.writeInt(1); out.writeUTF(two.directory);
        Files.createDirectories(legacy);
        new AtomicRecord(legacy.resolve("selection")).write(bytes.toByteArray());
        AdmissionTest.check(SelectionTest.locked(legacy, j -> j.snapshot(0)).availability == Availability.RECOVERY);
        AdmissionTest.check(SelectionTest.locked(legacy, j -> j.quarantineReport()).contains("cause not recorded"));
        SelectionTest.locked(legacy, j -> { j.retain(1); return null; }); // Rewritten as version 3.
        AdmissionTest.check(new DataInputStream(new ByteArrayInputStream(new AtomicRecord(legacy.resolve("selection")).read())).readInt() == 3);
        AdmissionTest.check(SelectionTest.locked(legacy, j -> j.snapshot(0)).active.equals(two.identity));
    }
    /** A journal-level cold start in a child JVM (its lease ends with it) with fixed pid 4242. */
    static String journalChild(Path root, String mode) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), TrialEvidenceTest.class.getName(), "journal", root.toString(), mode)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line = new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();
        AdmissionTest.check(child.waitFor(20, TimeUnit.SECONDS) && child.exitValue() == 0);
        return line;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) { child(args); return; }
        if (args.length > 0 && args[0].equals("journal")) {
            Path root = Paths.get(args[1]);
            StartupExits.Exit killed = new StartupExits.Exit(StartupExits.Kind.KILLED, "killed");
            try {
                SelectionJournal.Generation candidate = SelectionTest.locked(root, SelectionJournal::candidate);
                // "mismatch": evidence for a different attempt than the one recorded.
                SelectionJournal.Attempt attempt = args[2].equals("first") ? new SelectionJournal.Attempt(4242, 1000, 0, 0, null)
                    : new SelectionJournal.Attempt(4242, 1000, 4243, 1000, killed);
                SelectionTest.locked(root, j -> j.acquire(candidate, attempt));
                System.out.println("OK");
            } catch (ContractException failure) { System.out.println(failure.code.name()); }
            return;
        }
        killedStartsKeepThePayload();
        unexplainedStartsQuarantineWithReason();
        killBackstopAndReset();
        journalEvidenceIntegrityAndUpgrade();
        System.out.println("PASS trial evidence: killed starts keep embedded-65 -> admitted-72 (no quarantine, no REPLAY), unexplained/crash starts quarantine with recorded reason, kill backstop and onCreate reset, foreign evidence ignored, integrity cause kept, version-2 journal upgrade");
    }
}
