package com.lelloman.paravoidandroid.recovery;

import java.io.IOException;

/** Transport failure. Report 401/403 to participate in shared authentication suppression. */
public final class RecoveryUpdateException extends IOException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public RecoveryUpdateException(int status) {
        super("Recovery transport failed ("+status+")");
        if(status<400 || status>599) throw new IllegalArgumentException("HTTP error status required");
        this.status=status;
    }
}
