package com.lelloman.paravoidandroid.runtime;

import android.app.ApplicationExitInfo;
import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.runtime.lifecycle.StartupExits.Kind;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupExitsTest {
    @Test public void crashesAndHangsAreEvidenceKillsAreNot() {
        for (int reason : new int[] {ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE})
            assertEquals(Kind.FAILURE, AndroidStartupExits.classify(reason, 0, null).kind);
        for (int reason : new int[] {ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, 14, 15, 16})
            assertEquals(Kind.KILLED, AndroidStartupExits.classify(reason, 9, "remove task").kind);
        // Exiting by itself or an unrecognized reason still counts against the trial.
        assertEquals(Kind.UNKNOWN, AndroidStartupExits.classify(ApplicationExitInfo.REASON_EXIT_SELF, 0, null).kind);
        assertEquals(Kind.UNKNOWN, AndroidStartupExits.classify(ApplicationExitInfo.REASON_UNKNOWN, 0, null).kind);
        assertEquals(Kind.UNKNOWN, AndroidStartupExits.classify(99, 0, null).kind);
    }
    @Test public void labelsAreBoundedDiagnostics() {
        assertEquals("stopped by user (force stop)",
            AndroidStartupExits.classify(ApplicationExitInfo.REASON_USER_REQUESTED, 0, "force stop").label);
        String label = AndroidStartupExits.classify(ApplicationExitInfo.REASON_OTHER, 0, "x\n".repeat(100)).label;
        assertTrue(label.length() <= 48 && !label.contains("\n"));
    }
    @Test public void recoveryRecordNamesTheQuarantineNotTheRefusedFallback() {
        String report = "Payload r72 (version 72) is paused: 3 starts of its first run ended before the app finished starting.";
        String message = CompleteRuntime.PayloadUnavailable.message(report, ContractException.Code.REPLAY, null);
        assertTrue(message.startsWith(report));
        assertFalse(message.contains("REPLAY"));
        assertTrue(message.contains("older than one this device already accepted"));
        assertTrue(CompleteRuntime.PayloadUnavailable.message(null, ContractException.Code.UNAVAILABLE, null)
            .startsWith("The selected payload is paused."));
        assertTrue(CompleteRuntime.PayloadUnavailable.message(report, null, null).contains("(IO)"));
    }
    @Test public void supersededEmbeddedPayloadExplainsTheUpdateNotTheReplay() {
        // The phone incident: nothing selected, embedded 65 below the floor 72 raised by an update check.
        String superseded = "The app's built-in payload (version 65) is older than version 72, which this device already accepted, so it cannot run.";
        String message = CompleteRuntime.PayloadUnavailable.message(null, ContractException.Code.REPLAY, superseded);
        assertTrue(message.startsWith(superseded));
        assertTrue(message.endsWith("Check for updates and install the current version."));
        assertFalse(message.contains("REPLAY") || message.contains("paused") || message.contains("Retry"));
        assertTrue(CompleteRuntime.PayloadUnavailable.message(null, ContractException.Code.REPLAY, null)
            .contains("older than one this device already accepted"));
        // A paused payload keeps its own reason first, with the floor explanation as the replacement.
        String report = "Payload r72 (version 72) is paused: its stored files failed verification.";
        String paused = CompleteRuntime.PayloadUnavailable.message(report, ContractException.Code.REPLAY, superseded);
        assertTrue(paused.startsWith(report + " " + superseded));
    }
}
