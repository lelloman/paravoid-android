package com.lelloman.paravoidandroid.runtime;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CrashRecoveryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void durableGateSurvivesRestartAndExplicitRetry() throws Exception {
        CrashRecords records=new CrashRecords(temporary.newFolder());
        records.record("generation-a","main","worker",new IllegalStateException("saved data"));
        records=new CrashRecords(records.directory.toFile());
        assertTrue(records.pending("generation-a"));
        assertFalse(records.pending("generation-b"));
        assertTrue(records.details().contains("saved data"));
        records.acknowledge();
        assertFalse(records.pending("generation-a"));
        assertTrue(records.details().contains("saved data"));
    }
    @Test public void healthyPayloadAcknowledgesOnlyOtherPayloadsRecords() throws Exception {
        CrashRecords records=new CrashRecords(temporary.newFolder());
        records.record("old-contract:p72","main","main",new IllegalStateException("Lifecycle admission: REPLAY"));
        records.record("new-contract:p74","main","main",new IllegalStateException("own crash"));
        records.acknowledgeOthers("unknown"); // Unknown current identity never clears anything.
        assertTrue(records.pending("old-contract:p72"));
        records.acknowledgeOthers("new-contract:p74");
        assertFalse(records.pending("old-contract:p72"));
        assertTrue(records.pending("new-contract:p74"));
        records.acknowledge();
        Path damaged=records.record("old-contract:p72","main","main",new IllegalStateException("again"));
        Files.write(damaged,"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        records.acknowledgeOthers("new-contract:p74");
        assertTrue(records.pending("anything")); // A damaged record still gates.
    }
    @Test public void installingAShellApkAcknowledgesOlderRecords() throws Exception {
        CrashRecords records=new CrashRecords(temporary.newFolder());
        // Written by the previous shell (no selected payload, so the identity is unknown and gates everything).
        Path old=records.record("unknown","main","main",new IllegalStateException("Lifecycle admission: REPLAY"));
        long written=Long.parseLong(old.getFileName().toString().split("-")[0]);
        records.acknowledgeBefore(0); // Unknown install time: nothing changes.
        records.acknowledgeBefore(written); // Same millisecond: not older than the install.
        assertTrue(records.pending("anything"));
        records.acknowledgeBefore(written+1); // A newer APK was installed afterwards.
        assertFalse(records.pending("anything"));
        assertTrue(records.details().contains("REPLAY")); // Still readable as history.
        Thread.sleep(2);
        records.record("unknown","main","main",new IllegalStateException("after install"));
        records.acknowledgeBefore(written+1);
        assertTrue(records.pending("anything")); // Failures of the installed shell keep gating.
    }
    @Test public void recordsAreBoundedAndDamagedRecordsStillGate() throws Exception {
        CrashRecords records=new CrashRecords(temporary.newFolder());
        for(int i=0;i<9;i++) records.record("a","process","thread",new RuntimeException("x".repeat(100000)));
        assertEquals(4,records.records().size());
        for(Path p:records.records()) assertTrue(Files.size(p)<=CrashRecords.LIMIT);
        Files.write(records.records().get(0),"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(records.pending("different"));
        records.providerStarted();
        assertTrue(new CrashRecords(records.directory.toFile()).providerInterrupted());
        records.providerFinished();
        assertFalse(records.providerInterrupted());
    }
    @Test public void captureFailureStillDelegatesAndTerminates() {
        List<String> calls=new ArrayList<>();
        Throwable failure=new RuntimeException("original");
        CrashExceptionHandler handler=new CrashExceptionHandler((thread,error) -> {
            assertSame(failure,error); calls.add("delegate"); throw new IllegalStateException();
        },(thread,error) -> { calls.add("record"); throw new OutOfMemoryError(); },() -> calls.add("terminate"));
        try { handler.uncaughtException(Thread.currentThread(),failure); fail(); }
        catch(IllegalStateException expected) { }
        assertEquals(Arrays.asList("record","delegate","terminate"),calls);
    }
}
