package com.lelloman.paravoidandroid.recovery;

import java.io.OutputStream;

/** Shell-owned code only. Calls are serialized on a worker, never the UI/crash thread.
 * Implementations must have a public no-argument constructor, use bounded network
 * timeouts, and cooperate with cancellation. Never load application/payload classes.
 */
public interface RecoveryUpdateProvider {
    /** Return a signed Paravoid discovery envelope, including non-available outcomes. */
    byte[] check(RecoveryRequest request, Cancellation cancellation) throws Exception;
    /** Write exactly the admitted archive bytes. Do not close the shell-owned destination. */
    void download(RecoveryRequest request, RecoveryUpdate update, OutputStream destination,
                  Cancellation cancellation) throws Exception;
}
