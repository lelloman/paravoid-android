package com.lelloman.paravoidandroid.runtime;

import java.io.IOException;

/** Reconnect pacing and failure reporting for one push connection. Plain Java so host tests cover it. */
final class PushRetry {
    static final long INITIAL_DELAY=1000,MAX_DELAY=60000;
    /**
     * A connection must stay up this long before the backoff resets. Resetting on connect alone would retry every
     * second against a server that accepts the subscription and then closes it, for example on revoked credentials.
     */
    static final long STABLE_CONNECTION=30000;
    private long delay=INITIAL_DELAY;
    private String reported;

    /** Records the end of an attempt that stayed connected for connectedMillis, or -1 when it never connected. */
    void finished(long connectedMillis) {
        if(connectedMillis>=STABLE_CONNECTION) { delay=INITIAL_DELAY; reported=null; }
    }
    /** The wait before the next attempt; doubles up to MAX_DELAY until a stable connection resets it. */
    long nextDelay() { long wait=delay; delay=Math.min(MAX_DELAY,delay*2); return wait; }
    /**
     * The line to log for a failure, or null when the same reason was already logged since the last stable connection.
     * Messages are included only when the caller knows they hold no credentials or server data, and only for
     * IOExceptions: an IllegalArgumentException from URI parsing quotes the whole endpoint, query included.
     */
    String report(String phase,Throwable failure,boolean trustedMessage) {
        String type=failure.getClass().getSimpleName().isEmpty() ? failure.getClass().getName() : failure.getClass().getSimpleName();
        String message=trustedMessage && failure instanceof IOException ? failure.getMessage() : null;
        String reason=type+(message==null ? "" : ": "+clean(message));
        if(reason.equals(reported)) return null;
        reported=reason;
        return "Push "+phase+" failed: "+reason;
    }
    private static String clean(String message) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<message.length() && out.length()<120;i++) { char c=message.charAt(i); out.append(c<0x20 || c>0x7e ? '?' : c); }
        return out.toString();
    }
}
