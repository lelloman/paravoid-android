package com.lelloman.paravoidandroid.delivery;

import java.io.IOException;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

public final class AttemptPolicyTest {
    public static void main(String[] args) {
        AttemptPolicy p = new AttemptPolicy();
        check(p.begin(AttemptPolicy.Trigger.FOREGROUND, 0));
        check(!p.begin(AttemptPolicy.Trigger.CHECK_NOW, 1)); // one operation at a time
        p.finish(p.generation());
        check(!p.begin(AttemptPolicy.Trigger.FOREGROUND, 1));
        check(p.begin(AttemptPolicy.Trigger.FOREGROUND, AttemptPolicy.FOREGROUND_INTERVAL_MS));
        check(p.downloadAllowed(false, true));
        p.unmeteredOnly = true;
        check(!p.downloadAllowed(false, true)); check(p.downloadAllowed(false, false));
        p.automaticDownloads = false;
        check(!p.downloadAllowed(false, false)); check(p.downloadAllowed(true, true));
        for (long expected : new long[] {1000, 4000, 16000, -1})
            check(p.retryDelay(new IOException(), 0.5, p.generation()) == expected);
        p.finish(p.generation());
        for (int status : new int[] {401, 403}) {
            check(p.begin(AttemptPolicy.Trigger.RETRY, 1));
            check(p.retryDelay(new HttpTransport.Failure("http-status", status, -1), 0.5, p.generation()) == -1);
            p.finish(p.generation());
            check(!p.begin(AttemptPolicy.Trigger.EMPTY_BOOTSTRAP, 2));
            check(!p.downloadAllowed(true, false));
        }
        p.credentialsReplaced();
        check(p.begin(AttemptPolicy.Trigger.EMPTY_BOOTSTRAP, 2));
        check(p.retryDelay(new HttpTransport.Failure("http-status", 429, 9000), 0.5, p.generation()) == 3600000);
        check(p.retryDelay(new HttpTransport.Failure("http-status", 503, 12), 0.5, p.generation()) == 12000);
        check(p.retryDelay(new HttpTransport.Failure("http-status", 404, -1), 0.5, p.generation()) == -1);
        check(p.retryDelay(new HttpTransport.Failure("archive-hash-mismatch"), 0.5, p.generation()) == -1);
        p.cancel(); check(!p.downloadAllowed(true, false));
        check(p.retryDelay(new IOException(), 0.5, p.generation()) == -1);
        p.finish(p.generation());
        p.automaticChecks = false;
        check(!p.begin(AttemptPolicy.Trigger.FOREGROUND, 2));
        check(p.begin(AttemptPolicy.Trigger.CHECK_NOW, 2));
        long oldGeneration = p.generation(); p.credentialsReplaced();
        check(!p.current(oldGeneration));
        check(p.begin(AttemptPolicy.Trigger.RETRY, 2));
        check(p.retryDelay(new HttpTransport.Failure("http-status", 401, -1), 0.5, oldGeneration) == -1);
        p.finish(oldGeneration);
        check(!p.begin(AttemptPolicy.Trigger.RETRY, 3)); // stale completion cannot finish new attempt
        p.finish(p.generation()); p.automaticChecks = true;
        check(p.begin(AttemptPolicy.Trigger.EMPTY_BOOTSTRAP, 3)); // stale 401 did not suppress
        System.out.println("AttemptPolicyTest: " + TransportTest.assertions + " assertions passed");
    }
}
