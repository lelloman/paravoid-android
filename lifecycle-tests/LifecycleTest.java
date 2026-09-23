package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class LifecycleTest {
    /** Deliberately fake, test-source-only VPK evidence. These archives are NOT signed VPK vectors. */
    static final class Fixture implements VpkVerifier {
        final File source; final VerifiedRelease release;
        Fixture(Path directory, int version) throws Exception {
            this(directory, version, false);
        }
        Fixture(Path directory, int version, boolean reuse) throws Exception {
            source = directory.resolve("fixture-" + version + ".zip").toFile();
            List<InventoryEntry> inventory = new ArrayList<>();
            try (ZipOutputStream zip = reuse ? null : new ZipOutputStream(new FileOutputStream(source))) {
                for (String path : Arrays.asList("code/classes.dex", "resources.apk", "java-resources.jar", "resource-ledger.json")) {
                    byte[] bytes = (path + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    inventory.add(new InventoryEntry(path, bytes.length, sha256(bytes)));
                    if (zip != null) { zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry(); }
                }
            }
            ExpectedArchive identity = new ExpectedArchive("release-" + version, version, "a".repeat(64),
                sha256(Files.readAllBytes(source.toPath())), source.length());
            release = TestEvidence.release(identity, inventory);
        }
        public VerifiedRelease verifyDownloaded(File file, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
            if (!release.identity.equals(expected)) throw new ContractException(Code.INTEGRITY, "fake mismatch");
            return verifyEmbedded(file, policy, device);
        }
        public VerifiedRelease verifyEmbedded(File file, ShellPolicy policy, RequestScope device) throws ContractException {
            try {
                if (!release.identity.archiveSha256.equals(sha256(Files.readAllBytes(file.toPath()))))
                    throw new ContractException(Code.INTEGRITY, "fake integrity");
                return release;
            } catch (IOException e) { throw new ContractException(Code.IO, "fake IO"); }
        }
        public VerifiedRelease verifyRetained(File file, ShellPolicy policy, RequestScope device, ExpectedArchive expected) throws ContractException {
            return verifyDownloaded(file, policy, device, expected);
        }
    }
    static String sha256(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : AtomicRecord.hash(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    static final RuntimeLifecycle.Clock CLOCK = new RuntimeLifecycle.Clock() {
        public long unixSeconds() { return 10000; }
        public long elapsedSeconds() { return 100; }
        public String bootId() { return "test"; }
    };
    static RuntimeLifecycle lifecycle(Path root, Fixture fixture, boolean main) throws Exception {
        return new RuntimeLifecycle(root.toFile(), AdmissionTest.policy(Authentication.PUBLIC, "contract"),
            AdmissionTest.scope("contract", 30), fixture, CLOCK, main);
    }
    static AdmissionId admit(RuntimeLifecycle lifecycle, Fixture fixture) throws Exception {
        lifecycle.setCredentialScope(CredentialScope.publicAccess());
        return lifecycle.observeHead(TestEvidence.head(AdmissionTest.scope("contract", 30), fixture.release.identity.payloadVersion,
            9990, 10100, fixture.release.identity, "body-" + fixture.release.identity.payloadVersion), CredentialScope.publicAccess()).admission;
    }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory("facade-test-"); Fixture one = new Fixture(parent, 1);
        Path root = parent.resolve("store"); RuntimeLifecycle lifecycle = lifecycle(root, one, true); lifecycle.initializeNew();
        AdmissionId id = admit(lifecycle, one);
        AdmissionTest.check(lifecycle.stageDownloaded(one.source, id).status == StageStatus.PENDING);
        AdmissionTest.check(one.source.isFile() && lifecycle.snapshot().active == null);
        GenerationLease lease = lifecycle.acquireForProcess();
        AdmissionTest.check(!lease.files().resourcesApk.toPath().startsWith(root.resolve("staging")));
        AdmissionTest.check(!lease.files().resourcesApk.equals(one.source));
        lease.beforeUserCode();
        AdmissionTest.fails(Code.UNAVAILABLE, lease::firstFrameRendered);
        lease.applicationCreated(); AdmissionTest.check(lifecycle.snapshot().availability == Availability.TRIAL);
        lease.firstFrameRendered(); AdmissionTest.check(lifecycle.snapshot().availability == Availability.RUNNABLE);
        lifecycle.setCredentialScope(null);
        AdmissionTest.check(lifecycle.acquireForProcess() == lease); // Accepted offline execution is independent of credentials.
        Fixture two = new Fixture(parent, 2); RuntimeLifecycle staging = lifecycle(root, two, true);
        staging.stageDownloaded(two.source, admit(staging, two));
        AdmissionTest.check(staging.snapshot().waitingForProcesses && staging.snapshot().active.payloadVersion == 1);
        staging.cleanup(); AdmissionTest.check(lease.files().resourcesApk.isFile());
        lease.startupFailed(Code.UNAVAILABLE);
        AdmissionTest.fails(Code.UNAVAILABLE, lease::beforeUserCode);
        AdmissionTest.fails(Code.UNAVAILABLE, () -> lifecycle.retryQuarantined(one.release.identity));
        AdmissionTest.check(staging.snapshot().availability == Availability.RECOVERY);

        Path repairRoot = parent.resolve("repair"); RuntimeLifecycle repair = lifecycle(repairRoot, one, true); repair.initializeNew();
        repair.stageDownloaded(one.source, admit(repair, one));
        Path materialized;
        try (java.util.stream.Stream<Path> tree = Files.walk(repairRoot.resolve("generations"))) {
            materialized = tree.filter(p -> p.getFileName().toString().equals("resources.apk")).findFirst().get();
        }
        Files.setPosixFilePermissions(materialized, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.write(materialized, new byte[] {9});
        AdmissionTest.fails(Code.INTEGRITY, repair::acquireForProcess);
        AdmissionTest.check(repair.snapshot().active == null); // Pending pre-execution rejection never activates it.
        repair.stageDownloaded(one.source, admit(repair, one));
        AdmissionTest.check(repair.acquireForProcess().release().identity.equals(one.release.identity));
        repair.cleanup();
        System.out.println("PASS Lifecycle/GenerationLease facade: owned materialization, pending only, callbacks, offline execution, leased cleanup, corruption rejection and re-download");
    }
}
