package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;
import org.junit.Test;
import static org.junit.Assert.*;

public class RestartHoldTest {
    private static final long HOUR = 60L * 60 * 1000;
    private ExpectedArchive offer(String hash) { return new ExpectedArchive("release",42,"b".repeat(64),hash.repeat(64),100); }

    @Test public void idleAppRestartsAtOnce() {
        assertEquals(0, new RestartHold().remaining(offer("a"), false, 1000));
    }

    @Test public void busyAppHoldsUntilTheWaitRunsOut() {
        RestartHold hold = new RestartHold();
        assertEquals(RestartHold.MAX_WAIT_MILLIS, hold.remaining(offer("a"), true, 1000));
        assertEquals(RestartHold.MAX_WAIT_MILLIS - HOUR, hold.remaining(offer("a"), true, 1000 + HOUR));
        assertEquals(0, hold.remaining(offer("a"), true, 1000 + RestartHold.MAX_WAIT_MILLIS));
    }

    @Test public void idleInBetweenDoesNotRestartTheWait() {
        RestartHold hold = new RestartHold();
        hold.remaining(offer("a"), true, 0);
        hold.remaining(offer("a"), false, HOUR);
        assertEquals(RestartHold.MAX_WAIT_MILLIS - 2 * HOUR, hold.remaining(offer("a"), true, 2 * HOUR));
    }

    @Test public void aNewlyStagedArchiveStartsItsOwnWait() {
        RestartHold hold = new RestartHold();
        hold.remaining(offer("a"), true, 0);
        assertEquals(RestartHold.MAX_WAIT_MILLIS, hold.remaining(offer("c"), true, 20 * HOUR));
    }
}
