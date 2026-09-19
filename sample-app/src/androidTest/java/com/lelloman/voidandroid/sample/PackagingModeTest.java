package com.lelloman.voidandroid.sample;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dalvik.system.InMemoryDexClassLoader;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PackagingModeTest {
    private static final String ACTIVITY = "com.lelloman.voidandroid.sample.MainActivity";
    private static final String APPLICATION = "com.lelloman.voidandroid.sample.SampleApplication";

    @Test public void launcherReachesTheRealUserActivity() {
        Context context = ApplicationProvider.getApplicationContext();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(ACTIVITY, null, false);
        Activity activity = null;
        try {
            Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            assertNotNull(launcher);
            context.startActivity(launcher);
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            assertNotNull("Launcher must hand off to the user Activity", activity);
            instrumentation.waitForIdleSync();
            Activity launched = activity;
            instrumentation.runOnMainSync(() -> {
                assertEquals(ACTIVITY, launched.getClass().getName());
                assertNotNull(launched.getWindow().getDecorView().findViewWithTag("counter"));
            });
        } finally {
            instrumentation.removeMonitor(monitor);
            if (activity != null) {
                Activity launched = activity;
                instrumentation.runOnMainSync(launched::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }

    @Test public void applicationIsInitializedAndHasPackagingSpecificIdentity() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        boolean dynamic = context.getPackageName().endsWith(".void");
        assertEquals(context.getPackageName(), context.getSharedPreferences("startup", Context.MODE_PRIVATE).getString("package", null));
        Class<?> userApplication = context.getClassLoader().loadClass(APPLICATION);
        assertEquals(1, userApplication.getField("initializationCount").getInt(null));
        assertEquals(!dynamic, Application.class.isAssignableFrom(userApplication));
        assertEquals(dynamic, userApplication.getClassLoader() instanceof InMemoryDexClassLoader);
        if (dynamic) {
            assertEquals("com.lelloman.voidandroid.runtime.ShellApplication", context.getClass().getName());
            assertThrows(ClassNotFoundException.class, () -> context.getClass().getClassLoader().loadClass(APPLICATION));
            assertThrows(ClassNotFoundException.class, () -> context.getClass().getClassLoader().loadClass(ACTIVITY));
        } else {
            assertEquals(APPLICATION, context.getClass().getName());
        }
    }

    @Test public void directActivityLaunchAndRecreationPreserveState() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("sample", Context.MODE_PRIVATE).edit().clear().commit();
        // Deliberately bypass the bootstrap launcher, as restoration/deep links may do.
        Intent intent = new Intent().setClassName(context, ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertEquals(ACTIVITY, activity.getClass().getName());
                assertEquals(context.getPackageName().endsWith(".void"), activity.getClass().getClassLoader() instanceof InMemoryDexClassLoader);
                TextView counter = activity.getWindow().getDecorView().findViewWithTag("counter");
                assertEquals("Count: 0", counter.getText().toString());
                activity.getWindow().getDecorView().findViewWithTag("increment").performClick();
                assertEquals("Count: 1", counter.getText().toString());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                TextView counter = activity.getWindow().getDecorView().findViewWithTag("counter");
                assertEquals("Count: 1", counter.getText().toString());
            });
        }
    }
}
