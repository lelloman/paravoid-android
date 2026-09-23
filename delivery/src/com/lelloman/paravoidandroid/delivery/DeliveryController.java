package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Shell-owned asynchronous controls; no background service or payload loading required. */
public final class DeliveryController {
    private static final ScheduledExecutorService SIGNALS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "paravoid-cancellation"); thread.setDaemon(true); return thread;
    });
    public enum Activity { IDLE, CHECKING, WAITING_TO_RETRY, READY, CANCELLED, ERROR }
    public static final class Snapshot {
        public final LifecycleSnapshot lifecycle;
        public final DeliveryPreferences preferences;
        public final Activity activity;
        public final String errorCode; // fixed locally-generated code only, never exception/server text
        Snapshot(LifecycleSnapshot lifecycle, DeliveryPreferences preferences, Activity activity, String error) {
            this.lifecycle = lifecycle; this.preferences = preferences; this.activity = activity; errorCode = error;
        }
    }
    public interface Listener { void changed(Snapshot snapshot); }
    private final DeliveryClient client;
    private final Lifecycle lifecycle;
    private final RequestScope scope;
    private final DeliveryClient.Clock clock;
    private final Path preferenceFile;
    private final BooleanSupplier metered;
    private final ScheduledExecutorService worker;
    private final Executor callbacks;
    private final AttemptPolicy attempts = new AttemptPolicy();
    private DeliveryPreferences preferences = new DeliveryPreferences(true, true, false);
    private volatile Listener listener;
    private ScheduledFuture<?> pendingRetry;
    private long operation;
    private Activity activity = Activity.IDLE;
    private String error;
    private DeliveryLocks.Claim attemptLock;
    private final CancellationSignal cancellation;
    private String cancellationEpoch;
    private ScheduledFuture<?> cancellationWatch;

    // One attempt, including scheduled retries, across main/recovery controllers.
    // OS releases this lock on process death; never hold a lifecycle selection lock.
    private boolean claimAttempt() throws IOException {
        Files.createDirectories(preferenceFile.getParent());
        attemptLock = DeliveryLocks.tryAcquire(preferenceFile.resolveSibling(preferenceFile.getFileName() + ".attempt"));
        return attemptLock != null;
    }
    private Path retryFile() { return preferenceFile.resolveSibling(preferenceFile.getFileName() + ".retry"); }
    private void clearRetry() {
        try { Files.deleteIfExists(retryFile()); }
        catch (IOException failure) { fail("PREFERENCES_IO"); }
    }
    private void releaseAttempt() {
        if (cancellationWatch != null) { cancellationWatch.cancel(false); cancellationWatch = null; }
        try { if (attemptLock != null) attemptLock.close(); }
        catch (IOException failure) { fail("IO"); }
        finally {
            attemptLock = null;
        }
    }

    /** Pass one process-owned scheduled worker and a main-thread executor for callbacks. */
    public DeliveryController(DeliveryClient client, Lifecycle lifecycle, RequestScope scope, DeliveryClient.Clock clock,
            File preferenceFile, BooleanSupplier metered, ScheduledExecutorService worker, Executor callbacks) {
        this.client = Objects.requireNonNull(client); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.scope = scope; this.clock = clock; this.preferenceFile = preferenceFile.toPath();
        cancellation = new CancellationSignal(this.preferenceFile);
        this.metered = metered; this.worker = worker; this.callbacks = callbacks;
        worker.execute(() -> {
            try { preferences = DeliveryPreferences.read(this.preferenceFile); applyPreferences(); }
            catch (IOException failed) { fail("PREFERENCES_IO"); }
            publish();
        });
    }
    public void listen(Listener listener) { this.listener = listener; worker.execute(this::publish); }
    /** Resolve bootstrap from lifecycle state, never from the caller's process or Activity. */
    public void foreground() {
        worker.execute(() -> {
            try { foreground(lifecycle.snapshot().availability == Availability.EMPTY); }
            catch (ContractException failure) { fail(failure.code.name()); publish(); }
        });
    }
    public void foreground(boolean emptyBootstrap) {
        submit(emptyBootstrap ? AttemptPolicy.Trigger.EMPTY_BOOTSTRAP : AttemptPolicy.Trigger.FOREGROUND);
    }
    public void checkNow() { submit(AttemptPolicy.Trigger.CHECK_NOW); }
    public void retry() { submit(AttemptPolicy.Trigger.RETRY); }
    /** Invoke on APK replacement with a freshly obtained ApplicationInfo.sourceDir. */
    public void refreshInstalledApk(File baseApk) {
        client.cancelDownload();
        attempts.cancel();
        worker.execute(() -> {
            if (pendingRetry != null) pendingRetry.cancel(false);
            releaseAttempt();
            attempts.credentialsReplaced();
            operation = attempts.generation();
            try {
                client.installedApk(baseApk);
                String partition = client.credentialPartition();
                preferences = DeliveryPreferences.update(preferenceFile, saved ->
                        new DeliveryPreferences(saved.automaticChecks, saved.automaticDownloads, saved.unmeteredOnly,
                                saved.credentialPartition != null && !saved.credentialPartition.equals(partition)
                                        ? 0 : saved.lastAutomaticCheckSeconds, partition));
                applyPreferences();
                activity = Activity.IDLE; error = null;
            } catch (ContractException failure) { fail(failure.code.name()); }
            catch (IOException failure) { fail("IO"); }
            publish();
        });
    }
    public void cancelDownload() {
        try { cancellation.cancel(); }
        catch (IOException failure) { worker.execute(() -> { fail("PREFERENCES_IO"); publish(); }); }
        cancelLocal();
    }
    private void cancelLocal() {
        // Disconnect directly: worker may currently be blocked in an HTTP read.
        final long cancelledOperation;
        synchronized (attempts) { cancelledOperation = attempts.generation(); attempts.cancel(); }
        client.cancelDownload();
        worker.execute(() -> {
            if (attempts.generation() != cancelledOperation) return;
            if (pendingRetry != null) pendingRetry.cancel(false);
            attempts.finish(operation); activity = Activity.CANCELLED; error = null;
            if (attemptLock != null) clearRetry();
            releaseAttempt(); publish();
        });
    }
    public void preferences(DeliveryPreferences value) {
        worker.execute(() -> {
            try {
                preferences = DeliveryPreferences.update(preferenceFile, saved ->
                        new DeliveryPreferences(value.automaticChecks, value.automaticDownloads,
                                value.unmeteredOnly, saved.lastAutomaticCheckSeconds, saved.credentialPartition));
                applyPreferences();
            } catch (IOException failed) { fail("PREFERENCES_IO"); }
            publish();
        });
    }
    public void retainedPrevious(int count) {
        if (count < 0 || count > 3) throw new IllegalArgumentException("retention must be 0..3");
        worker.execute(() -> { try { lifecycle.setRetainedPrevious(count); }
            catch (ContractException failed) { fail(failed.code.name()); } publish(); });
    }
    /** UI must obtain explicit confirmation immediately before invoking this action. */
    public void retryQuarantinedAfterConfirmation(ExpectedArchive release) {
        worker.execute(() -> { try { lifecycle.retryQuarantined(release); }
            catch (ContractException failed) { fail(failed.code.name()); } publish(); });
    }
    private void applyPreferences() {
        attempts.automaticChecks = preferences.automaticChecks;
        attempts.automaticDownloads = preferences.automaticDownloads;
        attempts.unmeteredOnly = preferences.unmeteredOnly;
    }
    private void submit(AttemptPolicy.Trigger trigger) {
        worker.execute(() -> {
            // Refresh shared preferences before deciding, including changes made in recovery.
            try { preferences = DeliveryPreferences.read(preferenceFile); applyPreferences(); }
            catch (IOException failure) { fail("PREFERENCES_IO"); publish(); return; }
            long now = clock.unixSeconds();
            if (!attempts.begin(trigger, clock.elapsedMillis())) return;
            operation = attempts.generation();
            PendingRetry resume;
            boolean explicit = trigger == AttemptPolicy.Trigger.CHECK_NOW || trigger == AttemptPolicy.Trigger.RETRY;
            try {
                if (!claimAttempt()) { attempts.finish(operation); return; }
                preferences = DeliveryPreferences.read(preferenceFile); applyPreferences();
                resume = PendingRetry.read(retryFile());
                cancellationEpoch = cancellation.read();
                if (resume != null && !resume.cancellationEpoch.equals(cancellationEpoch)) {
                    clearRetry(); resume = null;
                    if (!explicit) { attempts.finish(operation); releaseAttempt(); activity = Activity.CANCELLED; publish(); return; }
                }
                if (resume != null && (explicit || !resume.partition.equals(client.credentialPartition()))) {
                    clearRetry(); resume = null;
                }
                if (resume == null && trigger == AttemptPolicy.Trigger.FOREGROUND && preferences.lastAutomaticCheckSeconds > 0
                        && now >= preferences.lastAutomaticCheckSeconds
                        && now - preferences.lastAutomaticCheckSeconds < 6 * 60 * 60) {
                    attempts.finish(operation); releaseAttempt(); return;
                }
            } catch (IOException failure) {
                attempts.finish(operation); releaseAttempt(); fail("PREFERENCES_IO"); publish(); return;
            }
            final long watchedOperation = operation;
            final String watchedEpoch = cancellationEpoch;
            final boolean watchedExplicit = resume == null ? explicit : resume.explicit;
            cancellationWatch = SIGNALS.scheduleWithFixedDelay(() -> {
                try {
                    if (!watchedEpoch.equals(cancellation.read())
                            || client.isDownloading() && !transferAllowed(watchedExplicit)) {
                        synchronized (attempts) {
                            if (attempts.current(watchedOperation)) cancelLocal();
                        }
                    }
                } catch (IOException failure) {
                    synchronized (attempts) {
                        if (attempts.current(watchedOperation)) cancelLocal();
                    }
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
            if (resume != null) {
                if (resume.retries > 3) {
                    attempts.finish(operation); clearRetry(); releaseAttempt(); fail("RETRY_EXHAUSTED"); publish(); return;
                }
                attempts.restoreRetries(resume.retries);
                long delay = Math.max(0, Math.min(3600, resume.dueSeconds - now)) * 1000;
                boolean resumedExplicit = resume.explicit;
                long token = operation;
                activity = Activity.WAITING_TO_RETRY;
                pendingRetry = worker.schedule(() -> run(token, resumedExplicit), delay, TimeUnit.MILLISECONDS);
                publish(); return;
            }
            if (!explicit) {
                try {
                    preferences = DeliveryPreferences.update(preferenceFile, saved ->
                            new DeliveryPreferences(saved.automaticChecks, saved.automaticDownloads,
                                    saved.unmeteredOnly, now, client.credentialPartition()));
                    applyPreferences();
                }
                catch (IOException failure) { attempts.finish(operation); releaseAttempt(); fail("PREFERENCES_IO"); publish(); return; }
            }
            run(operation, explicit);
        });
    }
    private void run(long token, boolean explicit) {
        if (!attempts.current(token)) return;
        activity = Activity.CHECKING; error = null; publish();
        try {
            if (!cancellationEpoch.equals(cancellation.read())) { cancelLocal(); return; }
            if (attempts.retryCount() > 0) {
                // Consume this retry durably before HTTP. Death during a request
                // cannot replay the same retry indefinitely on each process restart.
                new PendingRetry(client.credentialPartition(), clock.unixSeconds(),
                        attempts.retryCount() + 1, explicit, cancellationEpoch).write(retryFile());
            }
            preferences = DeliveryPreferences.read(preferenceFile); applyPreferences();
            if (!explicit && !preferences.automaticChecks) {
                activity = Activity.CANCELLED; attempts.finish(token); clearRetry(); releaseAttempt(); publish(); return;
            }
            DeliveryClient.Result result = client.check(scope, attempts.downloadAllowed(explicit, metered.getAsBoolean()), explicit, () -> {
                // The watcher disconnects blocked IO, but cannot be the only gate:
                // cancel can precede client registration or race the final handoff.
                if (!attempts.current(token) || !cancellationEpoch.equals(cancellation.read())
                        || client.isDownloading() && !transferAllowed(explicit))
                    throw new HttpTransport.Failure("cancelled");
            }, () -> transferAllowed(explicit));
            activity = result.stage == null ? Activity.IDLE : Activity.READY;
            if (result.status == HeadStatus.SHELL_UPDATE_REQUIRED) error = "SHELL_UPDATE_REQUIRED";
            else if (result.status == HeadStatus.NO_COMPATIBLE_RELEASE) error = "NO_COMPATIBLE_RELEASE";
        } catch (ContractException failure) { fail(failure.code.name()); }
        catch (IOException failure) {
            // Disk IO failures are terminal. Only transport statuses/truncation and recognizable
            // network exceptions enter the automatic network retry budget.
            boolean network = failure instanceof HttpTransport.Failure || failure instanceof java.net.SocketException
                    || failure instanceof java.net.SocketTimeoutException || failure instanceof java.net.UnknownHostException
                    || failure instanceof javax.net.ssl.SSLException;
            long delay = network ? attempts.retryDelay(failure, ThreadLocalRandom.current().nextDouble(), token) : -1;
            if (delay >= 0) {
                try {
                    new PendingRetry(client.credentialPartition(), clock.unixSeconds() + (delay + 999) / 1000,
                            attempts.retryCount(), explicit, cancellationEpoch).write(retryFile());
                    activity = Activity.WAITING_TO_RETRY;
                    pendingRetry = worker.schedule(() -> run(token, explicit), delay, TimeUnit.MILLISECONDS);
                    publish(); return;
                } catch (IOException storageFailure) {
                    fail("PREFERENCES_IO"); attempts.finish(token); releaseAttempt(); publish(); return;
                }
            }
            if (failure instanceof HttpTransport.Failure) {
                HttpTransport.Failure http = (HttpTransport.Failure) failure;
                if (http.code.equals("cancelled")) { activity = Activity.CANCELLED; error = null; }
                else fail(http.status == 401 || http.status == 403 ? "CREDENTIAL_UNAVAILABLE" : http.code);
            } else fail("IO");
        }
        attempts.finish(token); clearRetry(); releaseAttempt(); publish();
    }
    private void fail(String code) { activity = Activity.ERROR; error = code; }
    private boolean transferAllowed(boolean explicit) throws IOException {
        if (explicit) return true;
        DeliveryPreferences current = DeliveryPreferences.read(preferenceFile);
        return current.automaticChecks && current.automaticDownloads
            && (!current.unmeteredOnly || !metered.getAsBoolean());
    }
    private void publish() {
        Listener target = listener;
        if (target == null) return;
        LifecycleSnapshot state = null;
        try { state = lifecycle.snapshot(); }
        catch (ContractException failed) { fail(failed.code.name()); }
        Snapshot snapshot = new Snapshot(state, preferences, activity, error);
        callbacks.execute(() -> { if (listener == target) target.changed(snapshot); });
    }
}
