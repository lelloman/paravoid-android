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
