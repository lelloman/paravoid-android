package com.lelloman.paravoidcompat.hilt;

import static org.junit.Assert.*;
import android.content.Context;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the production Application, not HiltTestApplication or a replacement graph. */
@RunWith(AndroidJUnit4.class)
public class HiltProbeTest {
    @Test public void applicationAndActivityShareTheRealApplicationSingleton() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals(1, ProbeApplication.initializationCount);
        assertNotNull(ProbeApplication.initializedService);
        assertSame(context, ProbeApplication.initializedService.context);
        try (ActivityScenario<ProbeActivity> scenario = ActivityScenario.launch(ProbeActivity.class)) {
            scenario.onActivity(activity -> {
                assertSame(ProbeApplication.initializedService, activity.service);
                assertSame(context, activity.service.context);
                assertNotNull(activity.token);
                TextView status = activity.getWindow().getDecorView().findViewWithTag("hilt-status");
                assertEquals("Hilt Application + Activity injection: OK", status.getText().toString());
            });
        }
    }

    @Test public void recreationPreservesViewModelButCreatesANewActivityScope() {
        AtomicReference<ProbeViewModel> model = new AtomicReference<>();
        AtomicReference<ActivityToken> token = new AtomicReference<>();
        try (ActivityScenario<ProbeActivity> scenario = ActivityScenario.launch(ProbeActivity.class)) {
            scenario.onActivity(activity -> {
                model.set(activity.model);
                token.set(activity.token);
                assertSame(activity.service, activity.model.service);
                activity.model.state.set("count", 7);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertSame(model.get(), activity.model);
                assertEquals(Integer.valueOf(7), activity.model.state.get("count"));
                assertNotSame(token.get(), activity.token);
                assertSame(ProbeApplication.initializedService, activity.model.service);
                assertEquals(1, ProbeApplication.initializationCount);
            });
        }
    }
}
