package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.util.Objects;

/**
 * Platform evidence of how an earlier trial start ended, used only to decide whether that start
 * counts against the trial. A start killed from outside (the user, the OS) proves nothing about the
 * payload; a crash, an ANR or an unknown end does. The Android adapter reads ApplicationExitInfo
 * (API 30+). Evidence never clears a quarantine, selects a generation or changes replay floors.
 */
public interface StartupExits {
    enum Kind { FAILURE, KILLED, UNKNOWN }

    final class Exit {
        public static final Exit UNKNOWN = new Exit(Kind.UNKNOWN, "unknown");
        public final Kind kind;
        public final String label;
        public Exit(Kind kind, String label) {
            this.kind = Objects.requireNonNull(kind);
            this.label = label(label);
        }
        /** Bounded diagnostic text: never credential-bearing, never parsed. */
        static String label(String text) {
            String safe = text == null ? "" : text.replaceAll("[^A-Za-z0-9 ._:()/-]", "?");
            safe = safe.length() > 48 ? safe.substring(0, 48) : safe;
            return safe.isEmpty() ? "unknown" : safe;
        }
    }

    /** This process's id; 0 disables attempt tracking (every unfinished start then counts). */
    int currentPid();
    long currentMillis();
    /** How the process {@code pid}, which started a trial at {@code startedMillis}, ended. */
    Exit exit(int pid, long startedMillis);

    StartupExits NONE = new StartupExits() {
        public int currentPid() { return 0; }
        public long currentMillis() { return 0; }
        public Exit exit(int pid, long startedMillis) { return Exit.UNKNOWN; }
    };
}
