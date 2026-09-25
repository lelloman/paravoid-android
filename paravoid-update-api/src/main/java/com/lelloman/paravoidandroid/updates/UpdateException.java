package com.lelloman.paravoidandroid.updates;

import java.io.IOException;
/** Sanitized transport failure, including authentication suppression and retry hints. */
public final class UpdateException extends IOException {
    private static final long serialVersionUID=1L;
    public final int status;
    public final long retryAfterSeconds;
    public UpdateException(int status, long retryAfterSeconds) {
        super("Update transport failed");
        if (status != 0 && (status < 400 || status > 599) || retryAfterSeconds < -1) throw new IllegalArgumentException();
        this.status=status; this.retryAfterSeconds=retryAfterSeconds;
    }
}
