package com.lelloman.paravoidremote;

/** Trusted but broken update: failure must not authorize automatic code rollback. */
public final class Entry {
    public static String value() { throw new IllegalStateException("fixture startup failure"); }
}
