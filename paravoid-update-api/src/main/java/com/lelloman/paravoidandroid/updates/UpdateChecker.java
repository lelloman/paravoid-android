package com.lelloman.paravoidandroid.updates;

/** Shell-packaged public class with a public no-argument constructor. Runs off the UI thread. */
public interface UpdateChecker {
    /** Return the signed head/feed bytes. The shell verifies scope, signature, freshness and admission. */
    byte[] check(UpdateRequest request, Cancellation cancellation) throws Exception;
}
