package com.lelloman.paravoidandroid.runtime;

import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PushRetryTest {
    @Test public void backoffDoublesToCapWithoutStableConnection() {
        PushRetry retry=new PushRetry();
        long[] expected={1000,2000,4000,8000,16000,32000,60000,60000};
        for(long wait:expected) { retry.finished(-1); assertEquals(wait,retry.nextDelay()); }
    }
    @Test public void stableConnectionResetsBackoff() {
        PushRetry retry=new PushRetry();
        for(int i=0;i<10;i++) { retry.finished(-1); retry.nextDelay(); }
        retry.finished(PushRetry.STABLE_CONNECTION);
        assertEquals(1000,retry.nextDelay());
        retry.finished(-1);
        assertEquals(2000,retry.nextDelay());
    }
    @Test public void briefConnectionDoesNotResetBackoff() {
        PushRetry retry=new PushRetry();
        for(int i=0;i<10;i++) { retry.finished(-1); retry.nextDelay(); }
        // A server that accepts the subscription and closes at once must not cause one-second retries.
        retry.finished(0);
        assertEquals(60000,retry.nextDelay());
        retry.finished(PushRetry.STABLE_CONNECTION-1);
        assertEquals(60000,retry.nextDelay());
    }
    @Test public void reportsEachDistinctReasonOnceUntilStableConnection() {
        PushRetry retry=new PushRetry();
        IOException duplicate=new IOException("Duplicate push header upgrade");
        assertEquals("Push connection failed: IOException: Duplicate push header upgrade",retry.report("connection",duplicate,true));
        assertNull(retry.report("connection",new IOException("Duplicate push header upgrade"),true));
        assertEquals("Push connection failed: UnknownHostException: store.example",
            retry.report("connection",new UnknownHostException("store.example"),true));
        assertEquals("Push connection failed: IOException: Duplicate push header upgrade",retry.report("connection",duplicate,true));
        assertNull(retry.report("connection",duplicate,true));
        retry.finished(PushRetry.STABLE_CONNECTION-1);
        assertNull(retry.report("connection",duplicate,true));
        retry.finished(PushRetry.STABLE_CONNECTION);
        assertNotNull(retry.report("connection",duplicate,true));
    }
    @Test public void untrustedMessagesAreNeverReported() {
        PushRetry retry=new PushRetry();
        assertEquals("Push authentication failed: IllegalStateException",
            retry.report("authentication",new IllegalStateException("token=secret"),false));
        assertEquals("Push connection failed: IOException",retry.report("connection",new IOException("Bearer secret"),false));
        // URI parsing quotes the whole endpoint, query string included.
        assertEquals("Push connection failed: IllegalArgumentException",
            retry.report("connection",new IllegalArgumentException("Illegal character in query: wss://h/p?token=secret"),true));
    }
    @Test public void reportedMessagesAreBoundedPrintableAscii() {
        String line=new PushRetry().report("connection",new IOException("a\r\nbé"+"x".repeat(500)),true);
        assertTrue(line.startsWith("Push connection failed: IOException: a??b?x"));
        assertEquals("Push connection failed: IOException: ".length()+120,line.length());
        assertEquals("Push connection failed: IOException",new PushRetry().report("connection",new IOException(),true));
    }
}
