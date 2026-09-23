package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.lelloman.paravoidandroid.runtime.lifecycle.AdmissionTest.*;

public final class SpaceAdmissionTest {
    private static Process child(String mode, Path root) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
            System.getProperty("java.class.path"), SpaceAdmissionTest.class.getName(), mode, root.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static void ready(Process process) throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            check("ready".equals(reader.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream()))
                .readLine()).get(10, TimeUnit.SECONDS)));
        } finally { reader.shutdownNow(); }
    }
    private static RuntimeLifecycle lifecycle(Path root, LifecycleTest.Fixture fixture, AtomicLong free) throws Exception {
        return new RuntimeLifecycle(root.toFile(), policy(Authentication.PUBLIC, "contract"), scope("contract", 30),
            fixture, LifecycleTest.CLOCK, true, null, free::get);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Path root = Paths.get(args[1]);
            if (args[0].equals("busy")) { fails(Code.UNAVAILABLE, () -> SpaceAdmission.acquire(root)); return; }
            try (SpaceAdmission.Claim claim = SpaceAdmission.acquire(root)) {
                claim.check(); System.out.println("ready"); System.out.flush();
                if (args[0].equals("hold")) Thread.sleep(60000);
            }
            return;
        }
        Path parent = Files.createTempDirectory("space-admission-");
        Path locks = Files.createDirectory(parent.resolve("locks"));
        try (SpaceAdmission.Claim claim = SpaceAdmission.acquire(locks)) {
            claim.check();
            fails(Code.UNAVAILABLE, () -> SpaceAdmission.acquire(locks));
            // A failed same-JVM acquisition must not close another descriptor and
            // silently drop this process's POSIX lock before a second JVM probes it.
            Process peer = child("busy", locks);
            check(peer.waitFor(10, TimeUnit.SECONDS) && peer.exitValue() == 0);
        }
        Process owner = child("hold", locks);
        try {
            ready(owner); fails(Code.UNAVAILABLE, () -> SpaceAdmission.acquire(locks));
            owner.destroyForcibly(); check(owner.waitFor(10, TimeUnit.SECONDS));
            try (SpaceAdmission.Claim recovered = SpaceAdmission.acquire(locks)) { recovered.check(); }
        } finally { owner.destroyForcibly(); }
        check(Files.exists(locks.resolve("update-space.lock")));
        check(SpaceAdmission.required(100, 3) == SpaceAdmission.HEADROOM + 300);
        fails(Code.LIMIT_EXCEEDED, () -> SpaceAdmission.required(0, 3));
        fails(Code.LIMIT_EXCEEDED, () -> SpaceAdmission.required(Long.MAX_VALUE, 3));

        LifecycleTest.Fixture one = new LifecycleTest.Fixture(parent, 1);
        Path root = parent.resolve("store");
        AtomicLong free = new AtomicLong(512L * 1024 * 1024); // Below the former fixed 2 GiB allowance.
        RuntimeLifecycle life = lifecycle(root, one, free); life.initializeNew();
        AdmissionId id = LifecycleTest.admit(life, one);
        try (DownloadReservation reservation = life.reserveDownload(id)) {
            check(life.snapshot().active == null); // Snapshot/selection not held during network work.
            Path protectedStaging = Files.createDirectory(root.resolve("staging/owned-test"));
            fails(Code.UNAVAILABLE, () -> lifecycle(root, one, free).reserveDownload(id));
            fails(Code.UNAVAILABLE, () -> life.stageEmbedded(one.source));
            fails(Code.UNAVAILABLE, () -> life.stageDownloaded(one.source, id));
            check(Files.exists(protectedStaging)); // Busy preflight must not clean another writer's work.
            check(reservation.stage(one.source).status == StageStatus.PENDING);
            fails(Code.UNAVAILABLE, () -> reservation.stage(one.source));
        }
        GenerationLease lease = life.acquireForProcess(); lease.applicationCreated(); lease.firstFrameRendered();
        Path selectedBytes = lease.files().resourcesApk.toPath();

        LifecycleTest.Fixture two = new LifecycleTest.Fixture(parent, 2);
        RuntimeLifecycle next = lifecycle(root, two, free);
        AdmissionId nextId = LifecycleTest.admit(next, two);
        long required = SpaceAdmission.required(two.source.length(), 3);
        free.set(required - 1);
        Path abandoned = Files.createDirectory(root.resolve("staging/abandoned"));
        Path orphan = Files.createDirectory(root.resolve("generations/orphan"));
        Files.write(orphan.resolve("unused"), new byte[] {1});
        fails(Code.INSUFFICIENT_STORAGE, () -> next.reserveDownload(nextId));
        check(!Files.exists(abandoned) && !Files.exists(orphan));
        check(Files.exists(selectedBytes)); lease.beforeUserCode();
        check(next.snapshot().active.equals(one.release.identity) && next.snapshot().pending == null);
        fails(Code.REPLAY, () -> LifecycleTest.admit(life, one)); // Failed storage must not undo version knowledge.

        free.set(required);
        DownloadReservation cancelled = next.reserveDownload(nextId); cancelled.close(); cancelled.close();
        fails(Code.UNAVAILABLE, () -> cancelled.stage(two.source));
        try (DownloadReservation reservation = next.reserveDownload(nextId)) {
            next.setCredentialScope(null);
            fails(Code.CREDENTIAL_UNAVAILABLE, () -> reservation.stage(two.source));
        }
        AdmissionId renewed = LifecycleTest.admit(next, two);
        try (DownloadReservation reservation = next.reserveDownload(renewed)) {
            check(reservation.stage(two.source).status == StageStatus.PENDING);
        }
        check(next.snapshot().active.equals(one.release.identity));
        check(next.snapshot().pending.equals(two.release.identity));
        check(Files.exists(selectedBytes)); // Pending and active/leased are protected on cleanup.
        next.cleanup(); check(Files.exists(selectedBytes));
        free.set(512L * 1024 * 1024);
        Path embeddedRoot = parent.resolve("embedded-store");
        RuntimeLifecycle embedded = lifecycle(embeddedRoot, one, free); embedded.initializeNew();
        Path source = embeddedRoot.resolve("embedded-source.vpk");
        Files.write(source, new byte[] {9}); // Interrupted extraction from a previous owner.
        RuntimeLifecycle.EmbeddedReservation previous;
        try (RuntimeLifecycle.EmbeddedReservation reservation = embedded.reserveEmbedded(one.source.length())) {
            previous = reservation;
            check(!Files.exists(source));
            Files.copy(one.source.toPath(), reservation.sourceFile().toPath());
            embedded.cleanup(); check(Files.exists(source));
            fails(Code.UNAVAILABLE, () -> embedded.reserveEmbedded(one.source.length()));
            check(reservation.stage().status == StageStatus.PENDING);
            fails(Code.UNAVAILABLE, reservation::stage);
        }
        check(!Files.exists(source));
        try (RuntimeLifecycle.EmbeddedReservation reservation = embedded.reserveEmbedded(one.source.length())) {
            Files.write(reservation.sourceFile().toPath(), new byte[] {1});
            previous.close(); check(Files.exists(source)); // Old close must not delete a new owner's input.
            fails(Code.INTEGRITY, reservation::stage);
        }
        check(!Files.exists(source));
        check(embedded.snapshot().pending.equals(one.release.identity));
        System.out.println("PASS update-space admission: cross-JVM exclusion/death, same-JVM lock safety, exact budget, cleanup, cancellation, credential change, preserved active generation/replay floors");
    }
}
