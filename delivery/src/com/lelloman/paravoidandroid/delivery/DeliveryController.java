package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Shell-owned asynchronous controls; no background service or payload loading required. */
public final class DeliveryController {
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

    /** Pass one process-owned scheduled worker and a main-thread executor for callbacks. */
    public DeliveryController(DeliveryClient client, Lifecycle lifecycle, RequestScope scope, DeliveryClient.Clock clock,
            File preferenceFile, BooleanSupplier metered, ScheduledExecutorService worker, Executor callbacks) {
        this.client = Objects.requireNonNull(client); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.scope = scope; this.clock = clock; this.preferenceFile = preferenceFile.toPath();
        this.metered = metered; this.worker = worker; this.callbacks = callbacks;
        worker.execute(() -> {
            try { preferences = DeliveryPreferences.read(this.preferenceFile); applyPreferences(); }
            catch (IOException failed) { fail("PREFERENCES_IO"); }
            publish();
        });
    }
    public void listen(Listener listener) { this.listener = listener; worker.execute(this::publish); }
    public void foreground(boolean emptyBootstrap) {
        submit(emptyBootstrap ? AttemptPolicy.Trigger.EMPTY_BOOTSTRAP : AttemptPolicy.Trigger.FOREGROUND);
    }
    public void checkNow() { submit(AttemptPolicy.Trigger.CHECK_NOW); }
    public void retry() { submit(AttemptPolicy.Trigger.RETRY); }
    public void cancelDownload() {
        // Disconnect directly: worker may currently be blocked in an HTTP read.
        attempts.cancel(); client.cancelDownload();
        worker.execute(() -> {
            if (pendingRetry != null) pendingRetry.cancel(false);
            attempts.finish(operation); activity = Activity.CANCELLED; error = null; publish();
        });
    }
    public void preferences(DeliveryPreferences value) {
        worker.execute(() -> {
            try {
                DeliveryPreferences replacement = new DeliveryPreferences(value.automaticChecks, value.automaticDownloads,
                        value.unmeteredOnly, preferences.lastAutomaticCheckSeconds);
                replacement.write(preferenceFile); preferences = replacement; applyPreferences();
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
            long now = clock.unixSeconds();
            if (trigger == AttemptPolicy.Trigger.FOREGROUND && preferences.lastAutomaticCheckSeconds > 0
                    && now >= preferences.lastAutomaticCheckSeconds
                    && now - preferences.lastAutomaticCheckSeconds < 6 * 60 * 60) return;
            if (!attempts.begin(trigger, clock.elapsedMillis())) return;
            operation = attempts.generation();
            boolean explicit = trigger == AttemptPolicy.Trigger.CHECK_NOW || trigger == AttemptPolicy.Trigger.RETRY;
            if (!explicit) {
                preferences = new DeliveryPreferences(preferences.automaticChecks, preferences.automaticDownloads,
                        preferences.unmeteredOnly, now);
                try { preferences.write(preferenceFile); }
                catch (IOException failure) { attempts.finish(operation); fail("PREFERENCES_IO"); publish(); return; }
            }
            run(operation, explicit);
        });
    }
    private void run(long token, boolean explicit) {
        if (!attempts.current(token)) return;
        activity = Activity.CHECKING; error = null; publish();
        try {
            DeliveryClient.Result result = client.check(scope, attempts.downloadAllowed(explicit, metered.getAsBoolean()), explicit);
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
                activity = Activity.WAITING_TO_RETRY;
                pendingRetry = worker.schedule(() -> run(token, explicit), delay, TimeUnit.MILLISECONDS);
                publish(); return;
            }
            if (failure instanceof HttpTransport.Failure) {
                HttpTransport.Failure http = (HttpTransport.Failure) failure;
                if (http.code.equals("cancelled")) { activity = Activity.CANCELLED; error = null; }
                else fail(http.status == 401 || http.status == 403 ? "CREDENTIAL_UNAVAILABLE" : http.code);
            } else fail("IO");
        }
        attempts.finish(token); publish();
    }
    private void fail(String code) { activity = Activity.ERROR; error = code; }
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
