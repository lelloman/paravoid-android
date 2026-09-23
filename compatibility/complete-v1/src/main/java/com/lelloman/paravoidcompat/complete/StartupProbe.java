package com.lelloman.paravoidcompat.complete;

/** Fixture-only deterministic faults; production shell has no injected failure switch. */
final class StartupProbe {
    static void hit(String boundary) {
        if (BuildConfig.STARTUP_FAULT.equals(boundary)) throw new IllegalStateException("Startup fixture: " + boundary);
    }
}
