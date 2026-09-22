package com.lelloman.paravoidandroid.delivery;

import java.io.IOException;

/** Pure scheduling decisions. Caller uses an executor/timer; this class never sleeps. */
final class AttemptPolicy {
    static final long FOREGROUND_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long[] RETRY_MS = {1000, 4000, 16000};
    enum Trigger { FOREGROUND, EMPTY_BOOTSTRAP, CHECK_NOW, RETRY }
    boolean automaticChecks = true;
    boolean automaticDownloads = true;
    boolean unmeteredOnly;
    private boolean authSuppressed;
    private boolean busy;
    private boolean cancelled;
    private boolean hasChecked;
    private long lastCheckElapsedMs;
    private int retries;
    private long credentialGeneration;

    synchronized boolean begin(Trigger trigger, long elapsedMs) {
        if (elapsedMs < 0) throw new IllegalArgumentException("negative elapsed time");
        if (busy) return false;
        boolean explicit = trigger == Trigger.CHECK_NOW || trigger == Trigger.RETRY;
        if (authSuppressed && !explicit) return false;
        if (!explicit && !automaticChecks) return false;
        if (trigger == Trigger.FOREGROUND && hasChecked && elapsedMs >= lastCheckElapsedMs
                && elapsedMs - lastCheckElapsedMs < FOREGROUND_INTERVAL_MS) return false;
        if (explicit) authSuppressed = false;
        lastCheckElapsedMs = elapsedMs; hasChecked = true;
        busy = true; cancelled = false; retries = 0;
        return true;
    }

    synchronized boolean downloadAllowed(boolean explicitDownload, boolean metered) {
        return !cancelled && !authSuppressed && (explicitDownload
                || (automaticDownloads && (!unmeteredOnly || !metered)));
    }

    // Returns scheduling delay, or -1 for terminal failure. 0.0 <= jitter <= 1.0.
    synchronized long retryDelay(IOException failure, double jitter, long generation) {
        if (jitter < 0 || jitter > 1 || Double.isNaN(jitter)) throw new IllegalArgumentException("invalid jitter");
        if (generation != credentialGeneration || !busy || cancelled) return -1;
        long retryAfter = -1;
        if (failure instanceof HttpTransport.Failure) {
            HttpTransport.Failure f = (HttpTransport.Failure) failure;
            if (f.status == 401 || f.status == 403) {
                authSuppressed = true;
                return -1;
            }
            boolean retryable = f.code.equals("truncated-head") || f.code.equals("truncated-archive")
                    || (f.code.equals("http-status") && (f.status == 429 || f.status >= 500 && f.status <= 599));
            if (!retryable) return -1;
            retryAfter = f.retryAfterSeconds;
        }
        if (failure instanceof java.io.InterruptedIOException && !(failure instanceof java.net.SocketTimeoutException))
            return -1;
        // Generic IO includes disk failures: callers only pass failures from the network stage.
        if (retries == RETRY_MS.length) return -1;
        long delay = (long) (RETRY_MS[retries++] * (0.8 + 0.4 * jitter));
        return Math.max(delay, Math.min(3600, retryAfter) * 1000);
    }

    synchronized long generation() { return credentialGeneration; }
    synchronized boolean current(long generation) { return generation == credentialGeneration && !cancelled; }
    synchronized void finish(long generation) { if (generation == credentialGeneration) busy = false; }
    synchronized void cancel() { cancelled = true; }
    synchronized void credentialsReplaced() {
        credentialGeneration++;
        authSuppressed = false; cancelled = false; busy = false; retries = 0; hasChecked = false;
        // Integration must also cancel/close old requests, delete B-owned cache/partials,
        // and notify C to invalidate old admissions. This is not durable security state.
    }
}
