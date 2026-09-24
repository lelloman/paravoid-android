package com.lelloman.paravoidandroid.runtime;

import java.util.function.BiConsumer;

/** Captures once upstream, preserves the original handler, and never resumes a failed process. */
final class CrashExceptionHandler implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler previous;
    private final BiConsumer<Thread,Throwable> record;
    private final Runnable terminate;
    CrashExceptionHandler(Thread.UncaughtExceptionHandler previous, BiConsumer<Thread,Throwable> record, Runnable terminate) {
        this.previous=previous; this.record=record; this.terminate=terminate;
    }
    @Override public void uncaughtException(Thread thread, Throwable error) {
        try { record.accept(thread,error); } catch (Throwable ignored) { }
        try { if (previous!=null) previous.uncaughtException(thread,error); }
        finally { terminate.run(); }
    }
}
