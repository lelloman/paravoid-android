package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Host durability tests: fake VPK evidence, real facade, files, locks and process death. */
public final class PublicationTest {
    private static void check(boolean value) { AdmissionTest.check(value); }
    private static long generations(Path root) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(root.resolve("generations"))) {
            return entries.count();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Path parent = Paths.get(args[0]), root = parent.resolve("store");
            LifecycleTest.Fixture two = new LifecycleTest.Fixture(parent, 2, true);
            RuntimeLifecycle writer = new RuntimeLifecycle(root.toFile(),
                AdmissionTest.policy(Authentication.PUBLIC, "contract"), AdmissionTest.scope("contract", 30),
                two, LifecycleTest.CLOCK, true, null, root.toFile()::getUsableSpace, boundary -> {
                    if (!boundary.equals(args[1])) return;
                    if (args[2].equals("death")) Runtime.getRuntime().halt(73);
                    throw new IOException("injected pending journal failure: " + boundary);
                });
            AdmissionId admission = LifecycleTest.admit(writer, two);
            AdmissionTest.fails(ContractException.Code.IO, () -> writer.stageDownloaded(two.source, admission));
            return;
        }
        for (String boundary : Arrays.asList("content-synced", "renamed", "directory-synced")) {
            for (String mode : Arrays.asList("io", "death")) {
                Path parent = Files.createTempDirectory("publication-test-"), root = parent.resolve("store");
                LifecycleTest.Fixture one = new LifecycleTest.Fixture(parent, 1);
                LifecycleTest.Fixture two = new LifecycleTest.Fixture(parent, 2);
                RuntimeLifecycle active = LifecycleTest.lifecycle(root, one, true);
                active.initializeNew();
                active.stageDownloaded(one.source, LifecycleTest.admit(active, one));
                GenerationLease lease = active.acquireForProcess();
                lease.beforeUserCode(); lease.applicationCreated(); lease.firstFrameRendered();
                byte[] activeBytes = Files.readAllBytes(lease.files().resourcesApk.toPath());
                RuntimeLifecycle writer = LifecycleTest.lifecycle(root, two, true);
                AdmissionId admission = LifecycleTest.admit(writer, two);
                byte[] security = Files.readAllBytes(root.resolve("security"));
                byte[] selection = Files.readAllBytes(root.resolve("selection"));
                Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), PublicationTest.class.getName(),
                    parent.toString(), boundary, mode).inheritIO().start();
                try {
                    check(child.waitFor(20, TimeUnit.SECONDS));
                    check(child.exitValue() == (mode.equals("death") ? 73 : 0));
                } finally {
                    if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
                }
                // Publication already moved the immutable generation, but the selection
                // rename is the visibility boundary. Post-rename IO is an ambiguous success.
                check(generations(root) == 2);
                boolean committed = !boundary.equals("content-synced");
                LifecycleSnapshot snapshot = writer.snapshot();
                check(snapshot.active.equals(one.release.identity));
                check(snapshot.availability == Availability.RUNNABLE);
                check(committed ? snapshot.pending.equals(two.release.identity) : snapshot.pending == null);
                check(committed == snapshot.waitingForProcesses);
                if (!committed) check(Arrays.equals(selection, Files.readAllBytes(root.resolve("selection"))));
                check(Arrays.equals(security, Files.readAllBytes(root.resolve("security"))));
                check(Arrays.equals(activeBytes, Files.readAllBytes(lease.files().resourcesApk.toPath())));
                check(active.acquireForProcess() == lease);
                writer.cleanup();
                check(generations(root) == (committed ? 2 : 1));
                AdmissionTest.fails(ContractException.Code.REPLAY, () -> LifecycleTest.admit(active, one));
                StageResult retried = writer.stageDownloaded(two.source, admission);
                check(retried.status == (committed ? StageStatus.ALREADY_PENDING : StageStatus.PENDING));
                writer.cleanup();
                check(generations(root) == 2);
                check(writer.snapshot().pending.equals(two.release.identity));
                check(Arrays.equals(security, Files.readAllBytes(root.resolve("security"))));
                check(Arrays.equals(activeBytes, Files.readAllBytes(lease.files().resourcesApk.toPath())));
                System.out.println("PASS pending publication " + mode + " at " + boundary
                    + ": active lease/security preserved, valid selection, orphan cleanup, replay rejection and retry");
            }
        }
    }
}
