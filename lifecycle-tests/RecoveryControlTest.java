package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.GenerationLease;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.file.*;

/** Real journal/materialized-byte paths; fixture archives are test evidence, not signed VPKs. */
public final class RecoveryControlTest {
    private static void quarantineInChild(Path parent, Path root, int version) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), RecoveryControlTest.class.getName(),
            "child", parent.toString(), root.toString(), Integer.toString(version))
            .inheritIO().start();
        AdmissionTest.check(child.waitFor() == 0);
    }

    private static RuntimeLifecycle staged(Path parent, Path root, int version) throws Exception {
        LifecycleTest.Fixture fixture = new LifecycleTest.Fixture(parent, version);
        RuntimeLifecycle lifecycle = LifecycleTest.lifecycle(root, fixture, true);
        lifecycle.initializeNew();
        lifecycle.stageDownloaded(fixture.source, LifecycleTest.admit(lifecycle, fixture));
        return lifecycle;
    }

    private static void liveLease() throws Exception {
        Path parent = Files.createTempDirectory("recovery-live-");
        Path root = parent.resolve("store");
        RuntimeLifecycle lifecycle = staged(parent, root, 1);
        GenerationLease lease = lifecycle.acquireForProcess();
        lease.startupFailed(Code.UNAVAILABLE);
        AdmissionTest.check(lifecycle.snapshot().availability == Availability.RECOVERY);
        AdmissionTest.fails(Code.UNAVAILABLE, () -> lifecycle.retryQuarantined(lease.release().identity));
        AdmissionTest.check(lifecycle.snapshot().availability == Availability.RECOVERY);
    }

    private static void staleSelection() throws Exception {
        Path parent = Files.createTempDirectory("recovery-stale-");
        Path root = parent.resolve("store");
        RuntimeLifecycle one = staged(parent, root, 1);
        ExpectedArchive captured = one.snapshot().pending;
        quarantineInChild(parent, root, 1); // OS process exit releases the actual generation lease.
        AdmissionTest.check(one.snapshot().active.equals(captured));
        LifecycleTest.Fixture twoFixture = new LifecycleTest.Fixture(parent, 2);
        RuntimeLifecycle two = LifecycleTest.lifecycle(root, twoFixture, true);
        two.stageDownloaded(twoFixture.source, LifecycleTest.admit(two, twoFixture));
        AdmissionTest.check(two.snapshot().pending.equals(twoFixture.release.identity));
        byte[] selection = Files.readAllBytes(root.resolve("selection"));
        byte[] security = Files.readAllBytes(root.resolve("security"));
        AdmissionTest.fails(Code.UNAVAILABLE, () -> one.retryQuarantined(captured));
        AdmissionTest.check(java.util.Arrays.equals(selection, Files.readAllBytes(root.resolve("selection"))));
        AdmissionTest.check(java.util.Arrays.equals(security, Files.readAllBytes(root.resolve("security"))));
        AdmissionTest.check(one.snapshot().availability == Availability.RECOVERY);
        AdmissionTest.check(two.snapshot().pending.equals(twoFixture.release.identity));
        GenerationLease selected = two.acquireForProcess();
        AdmissionTest.check(selected.release().identity.equals(twoFixture.release.identity));
        AdmissionTest.fails(Code.UNAVAILABLE, () -> one.retryQuarantined(captured));
        AdmissionTest.check(two.snapshot().active.equals(twoFixture.release.identity));
    }

    private static void corruptBytes() throws Exception {
        Path parent = Files.createTempDirectory("recovery-corrupt-");
        Path root = parent.resolve("store");
        RuntimeLifecycle lifecycle = staged(parent, root, 1);
        quarantineInChild(parent, root, 1);
        ExpectedArchive captured = lifecycle.snapshot().active;
        Path materialized;
        try (java.util.stream.Stream<Path> tree = Files.walk(root.resolve("generations"))) {
            materialized = tree.filter(p -> p.getFileName().toString().equals("resources.apk")).findFirst().get();
        }
        Files.setPosixFilePermissions(materialized, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.write(materialized, new byte[] {9});
        AdmissionTest.fails(Code.INTEGRITY, () -> lifecycle.retryQuarantined(captured));
        AdmissionTest.check(lifecycle.snapshot().availability == Availability.RECOVERY);
        AdmissionTest.check(lifecycle.snapshot().active.equals(captured));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Path parent = Paths.get(args[1]); Path root = Paths.get(args[2]);
            LifecycleTest.Fixture fixture = new LifecycleTest.Fixture(parent, Integer.parseInt(args[3]), true);
            RuntimeLifecycle lifecycle = LifecycleTest.lifecycle(root, fixture, true);
            lifecycle.acquireForProcess().startupFailed(Code.UNAVAILABLE);
            return;
        }
        liveLease(); staleSelection(); corruptBytes();
        System.out.println("PASS recovery controls: live lease, stale confirmed identity, corrupt selected bytes");
    }
}
