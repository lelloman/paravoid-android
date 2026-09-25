package com.lelloman.paravoidandroid.updates;

import java.io.IOException;
/** Cooperative cancellation. Register a nonblocking action to interrupt blocking IO. */
public interface Cancellation {
    void check() throws IOException;
    AutoCloseable onCancel(Runnable disconnect);
}
