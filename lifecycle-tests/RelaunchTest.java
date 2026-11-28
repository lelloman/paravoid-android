package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Explicit relaunch of a retained previous generation. Cold starts run in child JVMs so generation
 * leases are real OS locks that end with the process. Fixture archives are not signed VPKs.
 */
public final class RelaunchTest {
    static final EmbeddedFloorTest.Fixtures FIXTURES = new EmbeddedFloorTest.Fixtures();

    static RuntimeLifecycle shell(Path root, String contract) throws Exception {
        return new RuntimeLifecycle(root.toFile(), AdmissionTest.policy(Authentication.PUBLIC, contract),
            AdmissionTest.scope(contract, 30), FIXTURES, LifecycleTest.CLOCK, true, null, StartupExits.NONE);
    }
    static LifecycleTest.Fixture fixture(Path parent, int version, String contract) throws Exception {
        LifecycleTest.Fixture fixture = new LifecycleTest.Fixture(parent, version, false, contract);
        FIXTURES.all.removeIf(f -> f.release.identity.payloadVersion == version);
        FIXTURES.all.add(fixture);
        return fixture;
    }
    static AdmissionId admit(RuntimeLifecycle lifecycle, LifecycleTest.Fixture fixture, String contract) throws Exception {
        lifecycle.setCredentialScope(CredentialScope.publicAccess());
        long version = fixture.release.identity.payloadVersion;
        return lifecycle.observeHead(TestEvidence.head(AdmissionTest.scope(contract, 30), version, 9990, 10100,
            fixture.release.identity, "body-" + contract + "-" + version), CredentialScope.publicAccess()).admission;
    }
    static String known() {
        StringBuilder text = new StringBuilder();
        for (LifecycleTest.Fixture f : FIXTURES.all)
            text.append(text.length() == 0 ? "" : ",").append(f.release.identity.payloadVersion).append(':').append(f.release.shellContractId);
        return text.toString();
    }
    static Process child(Path parent, Path root, String contract, String mode, String argument) throws Exception {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
            RelaunchTest.class.getName(), "child", parent.toString(), root.toString(), contract, known(), mode, argument)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static String line(Process process) throws Exception {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }
    /** One cold start: "healthy" reaches the first frame, "fail" reports a startup failure. */
    static String start(Path parent, Path root, String contract, String mode) throws Exception {
        Process process = child(parent, root, contract, mode, "-");
        String result = line(process);
        AdmissionTest.check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0);
        return result;
    }
    static void stop(Process process) throws Exception {
        process.destroyForcibly(); AdmissionTest.check(process.waitFor(10, TimeUnit.SECONDS));
    }
    static byte[] bytes(Path path) throws IOException { return Files.readAllBytes(path); }
    static long floor(Path root, String contract) throws Exception {
        return new AdmissionStore(root, AdmissionTest.policy(Authentication.PUBLIC, contract), new AdmissionStore.Clock() {
            public long unixSeconds() { return 10000; }
            public long elapsedSeconds() { return 100; }
            public String bootId() { return "test"; }
        }).payloadFloor();
    }
    static String directory(Path root, ExpectedArchive identity) throws Exception {
        return SelectionTest.locked(root, j -> j.retainedForRelaunch(identity, null, 0)).directory;
    }

    /** A store where version 1 then version 2 were delivered and both reached their first frame. */
    static final class Device {
        final Path parent = Files.createTempDirectory("relaunch-"), root = parent.resolve("store");
        final String contract;
        final LifecycleTest.Fixture one, two;
        final RuntimeLifecycle shell;
        Device(String contract) throws Exception {
            this.contract = contract;
            FIXTURES.all.clear();
            one = fixture(parent, 1, contract); two = fixture(parent, 2, contract);
            shell = shell(root, contract); shell.initializeNew();
            install(one); install(two);
        }
        void install(LifecycleTest.Fixture release) throws Exception {
            AdmissionTest.check(stage(release).status == StageStatus.PENDING);
            AdmissionTest.check(RelaunchTest.start(parent, root, contract, "healthy").equals("OK " + release.release.identity.payloadVersion));
        }
        StageResult stage(LifecycleTest.Fixture release) throws Exception {
            return shell.stageDownloaded(release.source, admit(shell, release, contract));
        }
        String start(String mode) throws Exception { return RelaunchTest.start(parent, root, contract, mode); }
        byte[] selection() throws IOException { return bytes(root.resolve("selection")); }
        byte[] security() throws IOException { return bytes(root.resolve("security")); }
    }

    /** Listing, lease rules, the hold and its replay limits, then returning to the newer version. */
    static void relaunchHoldAndReturn() throws Exception {
        Device device = new Device("contract");
        ExpectedArchive one = device.one.release.identity, two = device.two.release.identity;
        LifecycleSnapshot before = device.shell.snapshot();
        AdmissionTest.check(before.active.equals(two) && before.rollbackHold == null && !before.relaunchPending);
        AdmissionTest.check(before.retained.size() == 1); // Default retention: one previous version.
        RetainedGeneration listed = before.retained.get(0);
        AdmissionTest.check(listed.identity.equals(one) && "contract".equals(listed.shellContractId)
            && listed.healthySeconds == 10000 && listed.refusal == null && !listed.held);
        byte[] security = device.security();
        AdmissionTest.check(floor(device.root, "contract") == 2);

        // Only an exact retained identity: never the selected one, nor an unknown or altered one.
        byte[] selection = device.selection();
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.shell.relaunchRetained(two));
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.shell.relaunchRetained(AdmissionTest.release(1)));
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.shell.relaunchRetained(
            new ExpectedArchive(one.releaseId, one.payloadVersion, one.manifestSha256, one.archiveSha256, one.archiveSize + 1)));
        // A process still holding a lease on the retained bytes: refused, as an explicit retry is.
        Process holder = child(device.parent, device.root, device.contract, "lease", directory(device.root, one));
        try {
            AdmissionTest.check("LEASED".equals(line(holder)));
            AdmissionTest.fails(Code.UNAVAILABLE, () -> device.shell.relaunchRetained(one));
        } finally { stop(holder); }
        AdmissionTest.check(Arrays.equals(selection, device.selection()));

        // While a process runs version 2 the relaunch only becomes pending: no live swap.
        Process running = child(device.parent, device.root, device.contract, "hold", "-");
        try {
            AdmissionTest.check("RUNNING 2".equals(line(running)));
            device.shell.relaunchRetained(one);
            LifecycleSnapshot waiting = device.shell.snapshot();
            AdmissionTest.check(waiting.active.equals(two) && waiting.pending.equals(one) && waiting.relaunchPending
                && waiting.waitingForProcesses && waiting.rollbackHold.equals(two));
            AdmissionTest.check(device.start("healthy").equals("OK 2")); // Joins the running generation.
        } finally { stop(running); }
        AdmissionTest.check(device.start("healthy").equals("OK 1")); // The coordinated cold start selects it.
        LifecycleSnapshot relaunched = device.shell.snapshot();
        AdmissionTest.check(relaunched.availability == Availability.RUNNABLE && relaunched.active.equals(one)
            && relaunched.pending == null && !relaunched.relaunchPending && relaunched.rollbackHold.equals(two));
        AdmissionTest.check(relaunched.retained.size() == 1 && relaunched.retained.get(0).identity.equals(two)
            && relaunched.retained.get(0).held && relaunched.retained.get(0).refusal == null);

        // Replay protection is unchanged: same admission history and floor, older heads still refused.
        AdmissionTest.check(Arrays.equals(security, device.security()) && floor(device.root, "contract") == 2);
        device.shell.setCredentialScope(CredentialScope.publicAccess());
        AdmissionTest.fails(Code.REPLAY, () -> device.shell.observeHead(TestEvidence.head(AdmissionTest.scope("contract", 30), 1,
            9990, 10100, one, "body-contract-1"), CredentialScope.publicAccess()));
        // The hold: the version the user left is not installed again, even if offered and admitted.
        byte[] held = device.selection();
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.stage(device.two));
        AdmissionTest.check(Arrays.equals(held, device.selection()));
        // The left generation stays on disk while the hold exists, whatever the retention count.
        device.shell.setRetainedPrevious(0);
        AdmissionTest.check(Files.isDirectory(device.root.resolve("generations").resolve(directory(device.root, two))));
        AdmissionTest.check(device.shell.snapshot().retained.size() == 1);

        // Returning to the newer version is the same explicit relaunch; reaching it ends the hold.
        device.shell.relaunchRetained(two);
        AdmissionTest.check(device.start("healthy").equals("OK 2"));
        LifecycleSnapshot returned = device.shell.snapshot();
        AdmissionTest.check(returned.active.equals(two) && returned.rollbackHold == null && returned.availability == Availability.RUNNABLE);
        AdmissionTest.check(floor(device.root, "contract") == 2);
    }

    /** A strictly higher version installs normally during the hold and ends it. */
    static void higherVersionPassesTheHold() throws Exception {
        Device device = new Device("contract");
        device.shell.relaunchRetained(device.one.release.identity);
        // A confirmed relaunch waiting for its restart is not replaced by a delivery.
        LifecycleTest.Fixture three = fixture(device.parent, 3, "contract");
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.stage(three));
        AdmissionTest.check(device.start("healthy").equals("OK 1"));
        AdmissionTest.check(device.shell.snapshot().rollbackHold.payloadVersion == 2);
        device.install(three);
        LifecycleSnapshot updated = device.shell.snapshot();
        AdmissionTest.check(updated.active.payloadVersion == 3 && updated.rollbackHold == null);
        AdmissionTest.check(floor(device.root, "contract") == 3);
    }

    /** Clearing the hold resumes installs up to the left version and releases its protection. */
    static void clearedHold() throws Exception {
        Device device = new Device("contract");
        device.shell.relaunchRetained(device.one.release.identity);
        AdmissionTest.check(device.start("healthy").equals("OK 1"));
        String left = directory(device.root, device.two.release.identity);
        device.shell.setRetainedPrevious(0);
        device.shell.clearRollbackHold();
        LifecycleSnapshot cleared = device.shell.snapshot();
        AdmissionTest.check(cleared.rollbackHold == null && cleared.retained.isEmpty());
        AdmissionTest.check(!Files.exists(device.root.resolve("generations").resolve(left)));
        AdmissionTest.check(floor(device.root, "contract") == 2);
        AdmissionTest.check(device.stage(device.two).status == StageStatus.PENDING); // Downloaded again, not reused.
        AdmissionTest.check(device.start("healthy").equals("OK 2"));
    }

    /** A relaunch that fails at startup is quarantined; recovery can relaunch the newer one again. */
    static void failedRelaunchIsQuarantined() throws Exception {
        Device device = new Device("contract");
        ExpectedArchive one = device.one.release.identity, two = device.two.release.identity;
        device.shell.relaunchRetained(one);
        AdmissionTest.check(device.start("fail").equals("FAILED 1"));
        LifecycleSnapshot paused = device.shell.snapshot();
        AdmissionTest.check(paused.availability == Availability.RECOVERY && paused.active.equals(one)
            && paused.error == Code.UNAVAILABLE && paused.rollbackHold.equals(two));
        AdmissionTest.check(device.shell.quarantineReport().contains("failed while starting"));
        AdmissionTest.check(device.start("healthy").equals("UNAVAILABLE")); // Never falls back on its own.
        RetainedGeneration newer = paused.retained(two);
        AdmissionTest.check(newer != null && newer.refusal == null && newer.held);
        device.shell.relaunchRetained(two);
        AdmissionTest.check(device.start("healthy").equals("OK 2"));
        LifecycleSnapshot recovered = device.shell.snapshot();
        AdmissionTest.check(recovered.availability == Availability.RUNNABLE && recovered.active.equals(two)
            && recovered.rollbackHold == null && floor(device.root, "contract") == 2);
    }

    /** Altered retained bytes are refused before anything changes. */
    static void tamperedBytesRefused() throws Exception {
        Device device = new Device("contract");
        ExpectedArchive one = device.one.release.identity;
        Path components = device.root.resolve("generations").resolve(directory(device.root, one)).resolve("components");
        Path resources = components.resolve("resources.apk");
        byte[] original = bytes(resources);
        Files.setPosixFilePermissions(resources, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.write(resources, new byte[] {9});
        byte[] selection = device.selection(), security = device.security();
        AdmissionTest.fails(Code.INTEGRITY, () -> device.shell.relaunchRetained(one));
        AdmissionTest.check(Arrays.equals(selection, device.selection()) && Arrays.equals(security, device.security()));
        // A writable component is refused as well, even with the original content.
        Files.write(resources, original);
        AdmissionTest.fails(Code.INTEGRITY, () -> device.shell.relaunchRetained(one));
        AdmissionTest.check(Arrays.equals(selection, device.selection()));
        AdmissionTest.check(device.shell.snapshot().active.equals(device.two.release.identity));
    }

    /** After a shell contract change the previous generations are listed but cannot be relaunched. */
    static void otherContractRefused() throws Exception {
        Device device = new Device("contract1");
        LifecycleTest.Fixture three = fixture(device.parent, 3, "contract2");
        RuntimeLifecycle upgraded = shell(device.root, "contract2");
        AdmissionTest.check(upgraded.stageDownloaded(three.source, admit(upgraded, three, "contract2")).status == StageStatus.PENDING);
        AdmissionTest.check(start(device.parent, device.root, "contract2", "healthy").equals("OK 3"));
        upgraded.setRetainedPrevious(3);
        LifecycleSnapshot state = upgraded.snapshot();
        AdmissionTest.check(state.retained.size() == 2 && state.retained.get(0).identity.payloadVersion == 2
            && state.retained.get(1).identity.payloadVersion == 1);
        for (RetainedGeneration generation : state.retained)
            AdmissionTest.check(generation.refusal == Code.INCOMPATIBLE && "contract1".equals(generation.shellContractId));
        byte[] selection = bytes(device.root.resolve("selection"));
        AdmissionTest.fails(Code.INCOMPATIBLE, () -> upgraded.relaunchRetained(device.two.release.identity));
        AdmissionTest.check(Arrays.equals(selection, bytes(device.root.resolve("selection"))));
    }

    /** A delivered update waiting for its restart applies first. */
    static void pendingUpdateFirst() throws Exception {
        Device device = new Device("contract");
        LifecycleTest.Fixture three = fixture(device.parent, 3, "contract");
        AdmissionTest.check(device.stage(three).status == StageStatus.PENDING);
        byte[] selection = device.selection();
        AdmissionTest.fails(Code.UNAVAILABLE, () -> device.shell.relaunchRetained(device.one.release.identity));
        AdmissionTest.check(Arrays.equals(selection, device.selection()));
    }

    /** Generations kept before contracts were recorded show the contract named in their envelope. */
    static void unrecordedContract() throws Exception {
        Path root = Files.createTempDirectory("relaunch-unrecorded-");
        String contract = "c".repeat(64);
        Path archive = root.resolve("generations").resolve("legacy").resolve("archive.vpk");
        Files.createDirectories(archive.getParent());
        String body = Base64.getEncoder().encodeToString(("{\"shellContractId\":\"" + contract + "\"}").getBytes(StandardCharsets.UTF_8));
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("release.json"));
            zip.write(("{\"keyId\":\"k\",\"body\":\"" + body + "\",\"signature\":\"AA==\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        SelectionJournal.Generation legacy = new SelectionJournal.Generation("legacy", AdmissionTest.release(1));
        AdmissionTest.check(contract.equals(GenerationStore.unrecordedContract(root, legacy)));
        SelectionJournal.Generation missing = new SelectionJournal.Generation("missing", AdmissionTest.release(2));
        AdmissionTest.check(GenerationStore.unrecordedContract(root, missing) == null);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) { child(args); return; }
        relaunchHoldAndReturn();
        higherVersionPassesTheHold();
        clearedHold();
        failedRelaunchIsQuarantined();
        tamperedBytesRefused();
        otherContractRefused();
        pendingUpdateFirst();
        unrecordedContract();
        System.out.println("PASS relaunch: exact retained identity, lease and pending-update refusals, cold-start-only selection, "
            + "unchanged replay floor and admission history, hold blocks the left version and protects it, higher versions pass, "
            + "cleared hold, failed relaunch quarantined then return, tampered bytes and other contracts refused, unrecorded contracts");
    }

    private static void child(String[] args) throws Exception {
        Path parent = Paths.get(args[1]), root = Paths.get(args[2]);
        String contract = args[3], mode = args[5];
        for (String known : args[4].split(",")) {
            String[] parts = known.split(":");
            FIXTURES.all.add(new LifecycleTest.Fixture(parent, Integer.parseInt(parts[0]), true, parts[1]));
        }
        if (mode.equals("lease")) {
            SelectionTest.locked(root, j -> { ProcessLocks.leaseForProcess(root.resolve("lease-" + args[6])); return null; });
            System.out.println("LEASED"); System.out.flush();
            Thread.sleep(60000);
            return;
        }
        try {
            GenerationLease lease = shell(root, contract).acquireForProcess();
            long version = lease.release().identity.payloadVersion;
            if (mode.equals("fail")) { lease.startupFailed(Code.UNAVAILABLE); System.out.println("FAILED " + version); }
            else if (mode.equals("hold")) { System.out.println("RUNNING " + version); System.out.flush(); Thread.sleep(60000); }
            else { lease.applicationCreated(); lease.firstFrameRendered(); System.out.println("OK " + version); }
        } catch (ContractException failure) { System.out.println(failure.code.name()); }
        System.out.flush();
    }
}
