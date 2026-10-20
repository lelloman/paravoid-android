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
        String message = CompleteRuntime.PayloadQuarantined.message(report, ContractException.Code.REPLAY);
        assertTrue(message.startsWith(report));
        assertFalse(message.contains("REPLAY"));
        assertTrue(message.contains("older than one this device already accepted"));
        assertTrue(CompleteRuntime.PayloadQuarantined.message(null, ContractException.Code.UNAVAILABLE)
            .startsWith("The selected payload is paused."));
        assertTrue(CompleteRuntime.PayloadQuarantined.message(report, null).contains("(IO)"));
    }
}
