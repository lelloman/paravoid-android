package com.lelloman.paravoidandroid.recovery;

import java.util.List;

/**
 * Adds app-specific buttons, such as "Report bug", to the shell's crash recovery screen.
 *
 * <p>Shell-owned code with the same packaging rules as {@link RecoveryUpdateProvider}: supplied by
 * {@code paravoidRecoveryImplementation}, public with a public no-argument constructor, code-only,
 * and never loading application/payload classes. It runs in the payload-free recovery process,
 * so it keeps working when the payload crashes on startup.
 */
public interface RecoveryActionProvider {
    /**
     * The buttons to show, in order; empty for none. Called on the main thread each time the
     * screen is created: return quickly, without I/O. Use {@link RecoveryReport#locale} to pick
     * label texts and {@link RecoveryReport#crashed} to show an action only after a crash.
     */
    List<RecoveryAction> actions(RecoveryReport report);

    /**
     * Performs the action the user chose, on a worker thread, and tells the shell what to do
     * next. Use bounded network timeouts and cooperate with {@code cancellation}, which fires when
     * the user leaves the screen. A thrown exception shows a generic, retryable error.
     */
    RecoveryActionResult perform(String actionId, RecoveryReport report, Cancellation cancellation) throws Exception;
}
