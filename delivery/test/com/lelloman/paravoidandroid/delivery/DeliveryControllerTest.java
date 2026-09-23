package com.lelloman.paravoidandroid.delivery;

import java.nio.file.*;
import java.util.concurrent.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;

public final class DeliveryControllerTest {
    static final class Control implements AutoCloseable {
        final DeliveryClientTest.Setup setup;
        final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        final BlockingQueue<DeliveryController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        final Path preferences;
        DeliveryController controller;
        Control() throws Exception { this(false); }
        Control(boolean apkKey) throws Exception {
            setup = new DeliveryClientTest.Setup(apkKey);
            preferences = setup.f.dir.resolve("preferences");
            worker.setRemoveOnCancelPolicy(true); create();
            await(DeliveryController.Activity.IDLE);
        }
        void create() {
            controller = new DeliveryController(setup.client, setup.life, setup.scope, setup.clock,
                    preferences.toFile(), () -> true, worker, Runnable::run);
            controller.listen(snapshots::add);
        }
        DeliveryController.Snapshot await(DeliveryController.Activity activity) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (;;) {
                long remaining = deadline - System.nanoTime();
                DeliveryController.Snapshot value = snapshots.poll(Math.max(1, remaining), TimeUnit.NANOSECONDS);
                if (value == null || remaining <= 0) throw new AssertionError("no " + activity + " snapshot");
                if (value.activity == activity) return value;
            }
        }
        void barrier() throws Exception { worker.submit(() -> {}).get(5, TimeUnit.SECONDS); }
        public void close() throws java.io.IOException {
            worker.shutdownNow();
            try { check(worker.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            setup.close();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("cancel")) {
            new CancellationSignal(Paths.get(args[1])).cancel(); return;
        }
        try (Control c = new Control()) {
            c.setup.head();
            Fake archive = new Fake(200, ARCHIVE);
            CountDownLatch reading = new CountDownLatch(1);
            archive.stream = new java.io.InputStream() {
                @Override public int read() throws java.io.IOException {
                    reading.countDown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!archive.disconnected && System.nanoTime() < deadline) {
                        try { Thread.sleep(10); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                    }
                    if (!archive.disconnected) throw new AssertionError("cross-process cancel did not disconnect HTTP");
                    throw new java.io.IOException("disconnected");
                }
            };
            c.setup.f.responses.add(archive); c.controller.checkNow();
            check(reading.await(5, TimeUnit.SECONDS)); cancelFromProcess(c.preferences);
            c.await(DeliveryController.Activity.CANCELLED); c.barrier();
            check(archive.disconnected && c.setup.life.stages == 0 && c.setup.f.requests == 2);
            check(c.setup.life.reservations == 1 && c.setup.life.releasedReservations == 1 && !c.setup.life.reserved);
        }
        try (Control c = new Control()) {
            c.setup.f.responses.add(new Fake(429, new byte[0]).put("Retry-After", "3600"));
            c.controller.checkNow(); c.await(DeliveryController.Activity.WAITING_TO_RETRY);
            cancelFromProcess(c.preferences);
            c.await(DeliveryController.Activity.CANCELLED); c.barrier();
            check(c.setup.f.requests == 1);
            check(!Files.exists(c.preferences.resolveSibling("preferences.retry")));
        }
        try (Control c = new Control()) {
            Path retry = c.preferences.resolveSibling("preferences.retry");
            new PendingRetry(c.setup.client.credentialPartition(), 0, 1, true).write(retry);
            cancelFromProcess(c.preferences); // Original attempt's process is already gone.
            c.controller.foreground(true); c.await(DeliveryController.Activity.CANCELLED); c.barrier();
            check(c.setup.f.requests == 0 && !Files.exists(retry));
            c.setup.head(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.checkNow(); c.await(DeliveryController.Activity.READY);
            check(c.setup.f.requests == 2); // New explicit work is not permanently suppressed.
        }
        try (Control c = new Control()) {
            c.controller.preferences(new DeliveryPreferences(true, false, false)); c.barrier();
            c.setup.head(); c.controller.foreground(); c.barrier(); c.barrier();
            check(c.setup.f.requests == 1);
            c.create(); c.barrier();
            c.controller.foreground(); c.barrier(); c.barrier();
            check(c.setup.f.requests == 1); // Recovery UI alone is not empty bootstrap.
        }
        try (Control c = new Control()) {
            c.setup.head(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.checkNow();
            DeliveryController.Snapshot result = c.await(DeliveryController.Activity.READY);
            check(result.lifecycle.pending != null); check(c.setup.life.stages == 1);
            check(c.setup.f.requests == 2);
        }
        try (Control c = new Control()) {
            c.controller.preferences(new DeliveryPreferences(true, true, true)); c.barrier();
            c.setup.head(); c.controller.foreground(false); c.barrier();
            check(c.setup.f.requests == 1 && c.setup.life.stages == 0); // metered: head only
            c.controller.foreground(false); c.barrier(); check(c.setup.f.requests == 1);
            c.create(); c.barrier(); // restart controller: persisted interval still prevents automatic check
            c.controller.foreground(false); c.barrier(); check(c.setup.f.requests == 1);
            c.setup.cached(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.checkNow(); c.await(DeliveryController.Activity.READY);
            check(c.setup.life.stages == 1); // Explicit action overrides automatic metered preference.
        }
        try (Control c = new Control()) {
            c.setup.f.responses.add(new Fake(429, new byte[0]).put("Retry-After", "3600"));
            c.controller.checkNow(); c.await(DeliveryController.Activity.WAITING_TO_RETRY);
            check(c.setup.f.requests == 1); check(c.worker.getQueue().size() == 1);
            c.controller.cancelDownload(); c.await(DeliveryController.Activity.CANCELLED); c.barrier();
            check(c.worker.getQueue().isEmpty()); check(c.setup.f.requests == 1);
        }
        try (Control c = new Control()) {
            c.setup.f.responses.add(new Fake(403, new byte[0]));
            c.controller.foreground(true);
            check(c.await(DeliveryController.Activity.ERROR).errorCode.equals("CREDENTIAL_UNAVAILABLE"));
            c.controller.foreground(true); c.barrier(); check(c.setup.f.requests == 1);
            c.setup.head(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.retry(); c.await(DeliveryController.Activity.READY); check(c.setup.f.requests == 3);
        }
        try (Control c = new Control(true)) {
            c.setup.f.responses.add(new Fake(401, new byte[0]));
            c.controller.foreground(true); c.await(DeliveryController.Activity.ERROR);
            Path replacement = c.setup.f.dir.resolve("replacement.apk");
            Files.write(replacement, ApkGrantReaderTest.apk(new byte[] {2}, false, true));
            c.snapshots.clear();
            c.controller.refreshInstalledApk(replacement.toFile()); c.await(DeliveryController.Activity.IDLE);
            Fake head = c.setup.head(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.foreground(false); c.await(DeliveryController.Activity.READY);
            check(head.getRequestProperty("Authorization").equals("Bearer " + "B".repeat(43)));
            check(c.setup.life.stages == 1);
        }
        try (Control c = new Control()) {
            c.controller.preferences(new DeliveryPreferences(true, false, false)); c.barrier();
            c.setup.head(); c.controller.foreground(false); c.barrier();
            check(c.setup.f.requests == 1);
            // Production initializes the installed APK every time a process starts.
            c.controller.refreshInstalledApk(c.setup.f.dir.resolve("public.apk").toFile()); c.barrier();
            c.controller.foreground(false); c.barrier();
            check(c.setup.f.requests == 1);
            // A second already-initialized controller must see persisted settings.
            DeliveryController first = c.controller;
            c.create(); c.barrier();
            c.controller.preferences(new DeliveryPreferences(false, false, false)); c.barrier();
            c.setup.clock.wall += 21601; c.setup.clock.elapsed += 21601000;
            first.foreground(false); c.barrier();
            check(c.setup.f.requests == 1);
        }
        try (Control c = new Control()) {
            c.setup.f.responses.add(new Fake(429, new byte[0]).put("Retry-After", "3600"));
            c.controller.checkNow(); c.await(DeliveryController.Activity.WAITING_TO_RETRY);
            DeliveryController first = c.controller;
            c.create(); c.barrier();
            c.controller.checkNow(); c.barrier();
            check(c.setup.f.requests == 1); // Retry delay holds the shared attempt lock.
            first.cancelDownload(); c.barrier();
            c.setup.head(); c.setup.f.responses.add(new Fake(200, ARCHIVE));
            c.controller.checkNow(); c.await(DeliveryController.Activity.READY);
            check(c.setup.f.requests == 3); // Cancellation releases it for another controller.
        }
        try (Control c = new Control()) {
            Path retry = c.preferences.resolveSibling(c.preferences.getFileName() + ".retry");
            new PendingRetry(c.setup.client.credentialPartition(), c.setup.clock.wall + 3600, 2, false).write(retry);
            c.controller.foreground(true); c.await(DeliveryController.Activity.WAITING_TO_RETRY);
            check(c.setup.f.requests == 0); // Restart honors persisted Retry-After.
            c.controller.cancelDownload(); c.await(DeliveryController.Activity.CANCELLED); c.barrier();
            check(!Files.exists(retry));
            new PendingRetry(c.setup.client.credentialPartition(), 0, 3, false,
                    new CancellationSignal(c.preferences).read()).write(retry);
            c.setup.f.responses.add(new Fake(503, new byte[0]));
            c.controller.foreground(true); c.await(DeliveryController.Activity.ERROR); c.barrier();
            check(c.setup.f.requests == 1 && c.worker.getQueue().isEmpty());
            check(!Files.exists(retry)); // A restart does not reset the retry budget.
            new PendingRetry(c.setup.client.credentialPartition(), 0, 4, false,
                    new CancellationSignal(c.preferences).read()).write(retry);
            c.controller.foreground(true);
            check(c.await(DeliveryController.Activity.ERROR).errorCode.equals("RETRY_EXHAUSTED"));
            check(c.setup.f.requests == 1); // Death during the last retry cannot replay it.
        }
        try (Control c = new Control()) {
            c.setup.f.responses.add(new Fake(429, new byte[0]));
            c.controller.foreground(true); c.await(DeliveryController.Activity.WAITING_TO_RETRY);
            c.controller.preferences(new DeliveryPreferences(false, true, false)); c.barrier();
            c.await(DeliveryController.Activity.CANCELLED);
            check(c.setup.f.requests == 1); // Disabling automatic checks stops delayed automatic retries.
        }
        System.out.println("DeliveryControllerTest: " + TransportTest.assertions + " assertions passed");
    }
    private static void cancelFromProcess(Path preferences) throws Exception {
        Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), DeliveryControllerTest.class.getName(),
                "cancel", preferences.toString()).inheritIO().start();
        check(process.waitFor(5, TimeUnit.SECONDS)); check(process.exitValue() == 0);
    }
}
