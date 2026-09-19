package com.lelloman.paravoidcompat.hilt;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dalvik.system.InMemoryDexClassLoader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** No compile-time payload types: exercise the actual loaded graph in both modes. */
@RunWith(AndroidJUnit4.class)
public class HiltProbeTest {
    private static final String PACKAGE = "com.lelloman.paravoidcompat.hilt.";

    @Test public void applicationAndActivityShareTheRealApplicationSingleton() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals(1, appField("initializationCount"));
        Object service = appField("initializedService");
        assertNotNull(service);
        assertSame(context, field(service, "context"));
        assertSame(context, field(service, "application"));
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertSame(service, field(activity, "service"));
                assertSame(context, activity.getApplication());
                assertNotNull(field(activity, "token"));
                TextView status = activity.getWindow().getDecorView().findViewWithTag("hilt-status");
                assertEquals("Hilt Application + Activity injection: OK", status.getText().toString());
            });
        }
    }

    @Test public void recreationPreservesViewModelButCreatesANewActivityScope() {
        AtomicReference<Object> model = new AtomicReference<>();
        AtomicReference<Object> token = new AtomicReference<>();
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                model.set(field(activity, "model"));
                token.set(field(activity, "token"));
                assertSame(field(activity, "service"), field(model.get(), "service"));
                invoke(field(model.get(), "state"), "set", new Class<?>[] {String.class, Object.class}, "count", 7);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertSame(model.get(), field(activity, "model"));
                assertEquals(7, invoke(field(model.get(), "state"), "get", new Class<?>[] {String.class}, "count"));
                assertNotSame(token.get(), field(activity, "token"));
                assertSame(appField("initializedService"), field(model.get(), "service"));
                assertEquals(1, appField("initializationCount"));
            });
        }
    }

    @Test public void hiltAndApplicationClassesStayInsideThePayload() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        boolean shell = context.getPackageName().endsWith(".paravoid");
        for (String name : new String[] {PACKAGE + "ProbeApplication", PACKAGE + "ProbeActivity",
                "dagger.hilt.internal.GeneratedComponentManager", "dagger.hilt.android.internal.managers.ActivityComponentManager"}) {
            Class<?> type = context.getClassLoader().loadClass(name);
            assertEquals(name, shell, type.getClassLoader() instanceof InMemoryDexClassLoader);
            if (shell) assertThrows(ClassNotFoundException.class, () -> context.getClass().getClassLoader().loadClass(name));
        }
        if (shell) {
            assertEquals("com.lelloman.paravoidandroid.runtime.ShellApplication", context.getClass().getName());
            assertFalse(context.getClassLoader().loadClass("dagger.hilt.internal.GeneratedComponentManager").isInstance(context));
        } else {
            assertThrows(ClassNotFoundException.class, () -> context.getClassLoader()
                .loadClass("com.lelloman.paravoidandroid.hilt.internal.HiltLookup"));
        }
    }

    private static ActivityScenario<Activity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        return ActivityScenario.launch(new Intent().setClassName(context, PACKAGE + "ProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static Object appField(String name) {
        try {
            Context context = ApplicationProvider.getApplicationContext();
            return context.getClassLoader().loadClass(PACKAGE + "ProbeApplication").getField(name).get(null);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static Object field(Object instance, String name) {
        try { return instance.getClass().getField(name).get(instance); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static Object invoke(Object instance, String name, Class<?>[] parameters, Object... args) {
        try { return instance.getClass().getMethod(name, parameters).invoke(instance, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
