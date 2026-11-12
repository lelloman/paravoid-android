package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/**
 * Holds a policy-driven restart while the app reports it is busy. A busy app can hold one staged
 * archive for at most {@link #MAX_WAIT_MILLIS}, so a forgotten paused player cannot keep an
 * update from activating forever. The wait lives in the app process: when the process dies, the
 * next cold start activates the update anyway.
 */
final class RestartHold {
    static final long MAX_WAIT_MILLIS = 24L * 60 * 60 * 1000;
    private ExpectedArchive archive;
    private long since;

    /** How much longer to hold the restart of {@code pending}; 0 means restart now. */
    long remaining(ExpectedArchive pending, boolean busy, long now) {
        if (!busy) return 0;
        if (!pending.equals(archive)) { archive = pending; since = now; }
        return Math.max(0, MAX_WAIT_MILLIS - Math.max(0, now - since));
    }
}
