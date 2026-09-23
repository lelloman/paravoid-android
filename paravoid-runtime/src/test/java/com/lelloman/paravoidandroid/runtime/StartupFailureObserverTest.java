package com.lelloman.paravoidandroid.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupFailureObserverTest {
    private static Throwable crash(String type, String method) {
        Throwable failure = new IllegalStateException("fixture");
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement(type, method, "Fixture.java", 1)});
        return failure;
    }
    @Test public void recognizesOnlyExplicitFrameworkStartupBoundaries() {
        for (String method : new String[] {"performCreate", "performStart", "performResume"})
            assertTrue(StartupFailureObserver.isStartupCallback(crash("android.app.Activity", method)));
        for (String method : new String[] {"installProvider", "handleCreateService", "performLaunchActivity"})
            assertTrue(StartupFailureObserver.isStartupCallback(crash("android.app.ActivityThread", method)));
        for (String method : new String[] {"instantiateActivity", "instantiateProvider", "instantiateService", "instantiateReceiver"})
            assertTrue(StartupFailureObserver.isStartupCallback(crash("android.app.AppComponentFactory", method)));
        assertTrue(StartupFailureObserver.isStartupCallback(crash("android.content.ContentProvider", "attachInfo")));
        for (String method : new String[] {"handleServiceArgs", "handleReceiver", "handleMessage"})
            assertFalse(StartupFailureObserver.isStartupCallback(crash("android.app.ActivityThread", method)));
        assertFalse(StartupFailureObserver.isStartupCallback(crash("example.Activity", "performCreate")));
        assertFalse(StartupFailureObserver.isStartupCallback(crash("android.app.Activity", "onClick")));
    }
    @Test public void followsWrappedCausesAndAlwaysDelegatesOriginal() {
        Throwable original = new RuntimeException(crash("android.content.ContentProvider", "attachInfo"));
        AtomicInteger recorded = new AtomicInteger(), delegated = new AtomicInteger();
        Thread main = Thread.currentThread();
        StartupFailureObserver observer = new StartupFailureObserver(main, (thread, failure) -> {
            assertSame(main, thread); assertSame(original, failure); delegated.incrementAndGet();
        }, () -> true, failure -> { assertSame(original, failure); recorded.incrementAndGet(); });
        observer.uncaughtException(main, original);
        assertEquals(1, recorded.get()); assertEquals(1, delegated.get());
    }
    @Test public void laterAndOtherThreadCrashesDoNotQuarantine() {
        AtomicInteger recorded = new AtomicInteger(), delegated = new AtomicInteger();
        Thread main = Thread.currentThread();
        Throwable startup = crash("android.app.Activity", "performCreate");
        new StartupFailureObserver(main, (t, e) -> delegated.incrementAndGet(), () -> false,
            e -> recorded.incrementAndGet()).uncaughtException(main, startup);
        new StartupFailureObserver(main, (t, e) -> delegated.incrementAndGet(), () -> true,
            e -> recorded.incrementAndGet()).uncaughtException(new Thread(), startup);
        new StartupFailureObserver(main, (t, e) -> delegated.incrementAndGet(), () -> true,
            e -> recorded.incrementAndGet()).uncaughtException(main, crash("example.App", "laterWork"));
        assertEquals(0, recorded.get()); assertEquals(3, delegated.get());
    }
    @Test public void recordingFailureCannotReplaceOrSwallowOriginalCrash() {
        Throwable original = crash("android.app.Activity", "performCreate");
        AtomicInteger delegated = new AtomicInteger();
        new StartupFailureObserver(Thread.currentThread(), (t, e) -> {
            assertSame(original, e); delegated.incrementAndGet();
        }, () -> true, e -> { throw new OutOfMemoryError("reporting failed"); })
            .uncaughtException(Thread.currentThread(), original);
        assertEquals(1, delegated.get());
    }
}
