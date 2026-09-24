package com.lelloman.paravoidandroid.recovery;

import java.io.IOException;

public interface Cancellation {
    void check() throws IOException;
    /** Register a short, nonblocking disconnect action; called immediately if already cancelled.
     * Returns a registration to close when the operation completes. */
    AutoCloseable onCancel(Runnable disconnect);
}
