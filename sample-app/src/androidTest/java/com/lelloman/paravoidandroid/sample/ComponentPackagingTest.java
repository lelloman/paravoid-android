package com.lelloman.paravoidandroid.sample;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ComponentPackagingTest {
    @Test public void providerStartsBeforeApplicationOnCreateAndRemainsAccessible() {
        Context context = ApplicationProvider.getApplicationContext();
        android.os.Bundle report = context.getContentResolver().call(
            android.net.Uri.parse("content://" + context.getPackageName() + ".probe"), "report", null, null);
        assertNotNull(report);
        assertEquals(0, report.getInt("applicationCountAtStartup", -1));
        assertEquals(1, report.getInt("applicationCountNow", -1));
        assertTrue(report.getBoolean("hadApplicationContext"));
        assertEquals(context.getPackageName().endsWith(".paravoid"), report.getBoolean("payload"));
    }

    @Test public void receiverAndServiceUsePayloadClassesAfterApplicationInitialization() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Intent launch = new Intent().setClassName(context, "com.lelloman.paravoidandroid.sample.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Keep the app foreground so this tests packaging, not background-service policy.
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(launch)) {
            String request = UUID.randomUUID().toString();
            context.sendBroadcast(new Intent().setClassName(context, "com.lelloman.paravoidandroid.sample.ProbeReceiver")
                .putExtra("request", request));
            assertNotNull(context.startService(new Intent().setClassName(context, "com.lelloman.paravoidandroid.sample.ProbeService")
                .putExtra("request", request)));
            SharedPreferences report = context.getSharedPreferences("components", Context.MODE_PRIVATE);
            for (String kind : new String[] {"receiver", "service"}) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (!request.equals(report.getString(kind, null)) && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(kind, request, report.getString(kind, null));
                assertEquals(kind, context.getPackageName().endsWith(".paravoid"), report.getBoolean(kind + "Payload", false));
                assertEquals(kind, 1, report.getInt(kind + "ApplicationCount", -1));
            }
        }
    }
}
