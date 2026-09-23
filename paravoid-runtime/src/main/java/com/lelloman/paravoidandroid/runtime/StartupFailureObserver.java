package com.lelloman.paravoidandroid.runtime;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread startup observation, never a replacement for Android's crash handling. */
final class StartupFailureObserver implements Thread.UncaughtExceptionHandler {
    private final Thread owner;
    private final Thread.UncaughtExceptionHandler previous;
    private final BooleanSupplier eligible;
    private final Consumer<Throwable> record;

    StartupFailureObserver(Thread owner, Thread.UncaughtExceptionHandler previous,
            BooleanSupplier eligible, Consumer<Throwable> record) {
        this.owner = Objects.requireNonNull(owner); this.previous = Objects.requireNonNull(previous);
        this.eligible = Objects.requireNonNull(eligible); this.record = Objects.requireNonNull(record);
    }

    @Override public void uncaughtException(Thread thread, Throwable failure) {
        try {
            if (thread == owner && isStartupCallback(failure) && eligible.getAsBoolean()) record.accept(failure);
        } catch (Throwable recordingFailure) {
            // Failure reporting (including low-memory/disk failures) must not swallow,
            // replace or prevent Android from handling the original crash.
        } finally { previous.uncaughtException(thread, failure); }
    }

    static boolean isStartupCallback(Throwable failure) {
        // Inspect bounded cause chains; don't infer startup merely from "main" or
        // ActivityThread somewhere in an arbitrary later callback's stack.
        for (int cause = 0; failure != null && cause < 8; cause++, failure = failure.getCause()) {
            StackTraceElement[] stack = failure.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 256); i++) {
                String type = stack[i].getClassName(), method = stack[i].getMethodName();
                if (type.equals("android.app.AppComponentFactory") &&
                        (method.equals("instantiateActivity") || method.equals("instantiateProvider") ||
                         method.equals("instantiateService") || method.equals("instantiateReceiver"))) return true;
                if (type.equals("android.content.ContentProvider") && method.equals("attachInfo")) return true;
                if (type.equals("android.app.Activity") &&
                        (method.equals("performCreate") || method.equals("performStart") || method.equals("performResume"))) return true;
                if (type.equals("android.app.ActivityThread") &&
                        (method.equals("installProvider") || method.equals("handleCreateService") ||
                         method.equals("performLaunchActivity"))) return true;
            }
        }
        return false;
    }
}
