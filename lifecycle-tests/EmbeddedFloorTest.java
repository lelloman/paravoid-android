package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * An embedded-bootstrap shell whose embedded payload is below the payload floor, with nothing
 * selected: the state of a downstream phone (shell 65, payload 72 published). An update check ran
 * before the app's first launch and raised the floor to 72; every launch then failed with
 * "Lifecycle admission: REPLAY" staging the embedded 65, "Retry app" had no quarantined payload to
 * retry, and downloads stayed refused because the contract never bootstrapped. Cold starts are child
 * JVMs (real OS leases). Fixture archives are not signed VPKs.
 */
public final class EmbeddedFloorTest {
    static ShellPolicy policy(String contract) {
        ShellPolicy base = AdmissionTest.policy(Authentication.PUBLIC, contract);
        return new ShellPolicy("app", contract, base.trust, "https://test/", "stable", Authentication.PUBLIC,
            Bootstrap.EMBEDDED, true, false, 1, java.util.Collections.emptyMap(), new byte[0]);
    }
    /** Verifies every fixture of a test (a store holds releases of several versions and contracts). */
    static final class Fixtures implements VpkVerifier {
        final java.util.List<LifecycleTest.Fixture> all = new java.util.ArrayList<>();
        LifecycleTest.Fixture byVersion(ExpectedArchive expected) throws ContractException {
            for (LifecycleTest.Fixture f : all) if (f.release.identity.payloadVersion == expected.payloadVersion) return f;
            throw new ContractException(Code.INTEGRITY, "unknown fixture");
        }
        public VerifiedRelease verifyDownloaded(File file, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
            return byVersion(expected).verifyDownloaded(file, policy, device, expected);
        }
        public VerifiedRelease verifyRetained(File file, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
            return byVersion(expected).verifyRetained(file, policy, device, expected);
        }
        public VerifiedRelease verifyEmbedded(File file, ShellPolicy policy, RequestScope device) throws ContractException {
            try {
                String sha = LifecycleTest.sha256(Files.readAllBytes(file.toPath()));
                for (LifecycleTest.Fixture f : all) if (f.release.identity.archiveSha256.equals(sha)) return f.verifyEmbedded(file, policy, device);
            } catch (IOException e) { throw new ContractException(Code.IO, "fake IO"); }
            throw new ContractException(Code.INTEGRITY, "unknown fixture");
        }
    }
    static final Fixtures FIXTURES = new Fixtures();
    static RuntimeLifecycle shell(Path root, LifecycleTest.Fixture fixture, String contract) throws Exception {
        return new RuntimeLifecycle(root.toFile(), policy(contract), AdmissionTest.scope(contract, 30), FIXTURES,
            LifecycleTest.CLOCK, true, null, StartupExits.NONE);
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
    /** One cold start that reaches its first frame, in a child JVM. Prints "OK version" or the failure code. */
    static String start(Path parent, Path root, int version, String contract) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), EmbeddedFloorTest.class.getName(), "child", parent.toString(),
            root.toString(), Integer.toString(version), contract, known())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line = new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();
        AdmissionTest.check(child.waitFor(20, TimeUnit.SECONDS) && child.exitValue() == 0);
        return line;
    }
    static String known() {
        StringBuilder text = new StringBuilder();
        for (LifecycleTest.Fixture f : FIXTURES.all)
            text.append(text.length() == 0 ? "" : ",").append(f.release.identity.payloadVersion).append(':').append(f.release.shellContractId);
        return text.toString();
    }
    static byte[] bytes(Path path) throws IOException { return Files.readAllBytes(path); }

    static final class Phone {
        final Path parent = Files.createTempDirectory("embedded-floor-"), root = parent.resolve("store");
        final LifecycleTest.Fixture embedded65, published72;
        final RuntimeLifecycle shell, updates;
        final AdmissionId stale;
        /**
         * Shell 65 installed, never launched; its update process (older runtime, which did not gate head
         * observation) checked and observed 72: floor 72, selection empty, contract not bootstrapped.
         */
        Phone() throws Exception {
            FIXTURES.all.clear();
            embedded65 = fixture(parent, 65, "contract"); published72 = fixture(parent, 72, "contract");
            shell = shell(root, embedded65, "contract"); shell.initializeNew();
            updates = shell(root, published72, "contract");
            // This runtime refuses that early check outright: the floor cannot rise before bootstrap.
            updates.setCredentialScope(CredentialScope.publicAccess());
            byte[] security = bytes(root.resolve("security"));
            AdmissionTest.fails(Code.UNAVAILABLE, () -> admit(updates, published72, "contract"));
            AdmissionTest.check(Arrays.equals(security, bytes(root.resolve("security"))));
            // Recreate what the older runtime left behind.
            Path marker = root.resolve(RuntimeLifecycle.BOOTSTRAP_MARKER);
            Files.write(marker, "contract".getBytes("US-ASCII"));
            stale = admit(updates, published72, "contract");
            Files.delete(marker);
            LifecycleSnapshot state = shell.snapshot();
            AdmissionTest.check(state.availability == Availability.EMPTY && state.active == null && state.pending == null);
            // Shell 65's "Check again"/"Update": the download is refused (UNAVAILABLE), as on the phone.
            AdmissionTest.fails(Code.UNAVAILABLE, () -> updates.reserveDownload(stale));
            AdmissionTest.check(shell.embeddedSupersededReport() == null);
        }
        /** A launch: the embedded payload is refused by the floor, without changing selection or replay state. */
        void launchRefused() throws Exception {
            byte[] selection = bytes(root.resolve("selection")), security = bytes(root.resolve("security"));
            AdmissionTest.fails(Code.REPLAY, () -> shell.stageEmbedded(embedded65.source));
            AdmissionTest.check(Arrays.equals(selection, bytes(root.resolve("selection")))
                && Arrays.equals(security, bytes(root.resolve("security"))));
            AdmissionTest.check(shell.snapshot().availability == Availability.EMPTY);
            String report = shell.embeddedSupersededReport();
            AdmissionTest.check(report != null && report.contains("(version 65)") && report.contains("version 72")
                && !report.contains("REPLAY"));
        }
    }

    /** Recovery's "Check again" / "Update" then "Restart": the published release downloads and runs. */
    static void stuckPhoneRecoversByDownload(int published) throws Exception {
        Phone phone = new Phone();
        phone.launchRefused();
        phone.launchRefused(); // Every launch, the same refusal and no other change.
        // "Retry app" has nothing to retry: no payload is selected, let alone quarantined.
        AdmissionTest.fails(Code.UNAVAILABLE, () -> phone.shell.retryQuarantined(phone.published72.release.identity));
        LifecycleTest.Fixture release = published == 72 ? phone.published72 : fixture(phone.parent, published, "contract");
        RuntimeLifecycle updates = shell(phone.root, release, "contract");
        AdmissionId id = admit(updates, release, "contract"); // Check again: allowed now.
        try (DownloadReservation download = updates.reserveDownload(id)) {
            AdmissionTest.check(download.stage(release.source).status == StageStatus.PENDING);
        }
        AdmissionTest.check(phone.shell.snapshot().pending.payloadVersion == published);
        AdmissionTest.check(start(phone.parent, phone.root, published, "contract").equals("OK " + published));
        LifecycleSnapshot running = phone.shell.snapshot();
        AdmissionTest.check(running.availability == Availability.RUNNABLE && running.active.payloadVersion == published
            && running.lastHealthy.payloadVersion == published);
        AdmissionTest.check(new String(bytes(phone.root.resolve(RuntimeLifecycle.BOOTSTRAP_MARKER)), "US-ASCII").equals("contract")
            && !Files.exists(phone.root.resolve(RuntimeLifecycle.SUPERSEDED_MARKER)) && phone.shell.embeddedSupersededReport() == null);
        // The floor never moved down: 65 stays refused.
        AdmissionTest.fails(Code.REPLAY, () -> phone.shell.stageEmbedded(phone.embedded65.source));
    }

    /** A new shell APK over the stuck state (data kept): its embedded payload is above the floor and runs. */
    static void newShellOverStuckPhone() throws Exception {
        Phone phone = new Phone();
        phone.launchRefused(); // Also as left by a shell with this runtime: a marker for the old contract.
        LifecycleTest.Fixture embedded74 = fixture(phone.parent, 74, "contract74");
        RuntimeLifecycle shell74 = shell(phone.root, embedded74, "contract74");
        AdmissionTest.check(shell74.embeddedSupersededReport() == null); // Another contract's refusal is not this one's.
        AdmissionTest.fails(Code.UNAVAILABLE, () -> admit(shell74, embedded74, "contract74")); // Not bootstrapped yet.
        AdmissionTest.check(shell74.stageEmbedded(embedded74.source).status == StageStatus.PENDING);
        AdmissionTest.check(start(phone.parent, phone.root, 74, "contract74").equals("OK 74"));
        AdmissionTest.check(shell74.snapshot().availability == Availability.RUNNABLE
            && new String(bytes(phone.root.resolve(RuntimeLifecycle.BOOTSTRAP_MARKER)), "US-ASCII").equals("contract74")
            && !Files.exists(phone.root.resolve(RuntimeLifecycle.SUPERSEDED_MARKER)));
    }

    /**
     * A contract-changing shell whose embedded payload is older than the selected one: the old
     * selection cannot load under the new contract and the embedded one is below the floor. Update
     * checks and downloads for the new contract are allowed, and a delivered release starts.
     */
    static void contractChangeBelowFloor() throws Exception {
        Path parent = Files.createTempDirectory("embedded-floor-contract-"), root = parent.resolve("store");
        FIXTURES.all.clear();
        LifecycleTest.Fixture embedded65 = fixture(parent, 65, "contract"), downloaded72 = fixture(parent, 72, "contract");
        RuntimeLifecycle old = shell(root, embedded65, "contract"); old.initializeNew();
        AdmissionTest.check(old.stageEmbedded(embedded65.source).status == StageStatus.PENDING);
        AdmissionTest.check(start(parent, root, 65, "contract").equals("OK 65"));
        RuntimeLifecycle oldUpdates = shell(root, downloaded72, "contract");
        AdmissionTest.check(oldUpdates.stageDownloaded(downloaded72.source, admit(oldUpdates, downloaded72, "contract")).status == StageStatus.PENDING);
        AdmissionTest.check(start(parent, root, 72, "contract").equals("OK 72"));
        LifecycleTest.Fixture embedded70 = fixture(parent, 70, "contract2"), delivered75 = fixture(parent, 75, "contract2");
        RuntimeLifecycle shell2 = shell(root, embedded70, "contract2");
        AdmissionTest.check(start(parent, root, 70, "contract2").equals("INCOMPATIBLE")); // 72 cannot load here.
        // Paused with its real cause (not "failed verification"); no byte repair of an incompatible release.
        LifecycleSnapshot paused = shell2.snapshot();
        AdmissionTest.check(paused.availability == Availability.RECOVERY && paused.error == Code.INCOMPATIBLE
            && shell2.quarantineReport().contains("does not fit the installed app version"));
        AdmissionTest.fails(Code.REPLAY, () -> shell2.stageEmbedded(embedded70.source));
        AdmissionTest.check(shell2.embeddedSupersededReport().contains("(version 70) is older than version 72"));
        RuntimeLifecycle updates2 = shell(root, delivered75, "contract2");
        try (DownloadReservation download = updates2.reserveDownload(admit(updates2, delivered75, "contract2"))) {
            AdmissionTest.check(download.stage(delivered75.source).status == StageStatus.PENDING);
        }
        AdmissionTest.check(start(parent, root, 75, "contract2").equals("OK 75"));
        AdmissionTest.check(shell2.snapshot().active.payloadVersion == 75 && shell2.embeddedSupersededReport() == null);
    }

    /** A damaged or foreign marker never opens update writers before bootstrap. */
    static void markerValidation() throws Exception {
        Phone phone = new Phone();
        Path marker = phone.root.resolve(RuntimeLifecycle.SUPERSEDED_MARKER);
        for (String text : new String[] {"other\n65\n72", "contract\n72\n65", "contract\nx\n72", "contract\n65", "contract\n65\n72\n"}) {
            Files.write(marker, text.getBytes("US-ASCII"));
            AdmissionTest.check(phone.shell.embeddedSupersededReport() == null);
            AdmissionTest.fails(Code.UNAVAILABLE, () -> phone.updates.reserveDownload(phone.stale));
        }
        Files.write(marker, "contract\n65\n72".getBytes("US-ASCII"));
        AdmissionTest.check(phone.shell.embeddedSupersededReport() != null);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            Path parent = Paths.get(args[1]), root = Paths.get(args[2]);
            LifecycleTest.Fixture fixture = null;
            for (String known : args[5].split(",")) {
                String[] parts = known.split(":");
                LifecycleTest.Fixture f = new LifecycleTest.Fixture(parent, Integer.parseInt(parts[0]), true, parts[1]);
                FIXTURES.all.add(f);
                if (parts[0].equals(args[3])) fixture = f;
            }
            try {
                GenerationLease lease = shell(root, fixture, args[4]).acquireForProcess();
                lease.applicationCreated(); lease.firstFrameRendered();
                System.out.println("OK " + lease.release().identity.payloadVersion);
            } catch (ContractException failure) { System.out.println(failure.code.name()); }
            System.out.flush();
            return;
        }
        stuckPhoneRecoversByDownload(72);
        stuckPhoneRecoversByDownload(73);
        newShellOverStuckPhone();
        contractChangeBelowFloor();
        markerValidation();
        System.out.println("PASS embedded floor: no head observation before bootstrap; embedded payload below the floor with nothing selected is refused without state change and explained; then update checks/downloads run (72 or 73) and the delivered release starts; a new shell above the floor starts over that state; contract change below the floor recovers by download; damaged/foreign markers ignored");
    }
}
