package com.lelloman.paravoidcompat.hilt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.CoreComponentFactory;
import dagger.hilt.android.EntryPointAccessors;
import dalvik.system.InMemoryDexClassLoader;

/** This fixture intentionally exercises AndroidX's internal component-wrapper contract. */
@SuppressWarnings("RestrictedApi")
public final class WrappedProbeReceiver extends BroadcastReceiver implements CoreComponentFactory.CompatWrapped {
    @Override public Object getWrapper() { return new ActualReceiver(); }
    @Override public void onReceive(Context context, Intent intent) {
        throw new IllegalStateException("AndroidX component factory was not delegated to");
    }

    public static final class ActualReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            ProbeService service = EntryPointAccessors.fromApplication(context, ProbeActivity.Services.class).service();
            if (ProbeApplication.initializationCount != 1 || service != ProbeApplication.initializedService ||
                    service != ProbeStartupInitializer.service) throw new IllegalStateException("Cold receiver graph/startup mismatch");
            boolean payload = getClass().getClassLoader() instanceof InMemoryDexClassLoader;
            if (payload != context.getPackageName().endsWith(".paravoid")) throw new IllegalStateException("Wrong receiver loader");
            if (!context.getSharedPreferences("hilt-probe", Context.MODE_PRIVATE).edit()
                    .putString("receiverRun", intent.getStringExtra("probeRun")).commit()) {
                throw new IllegalStateException("Could not save receiver test result");
            }
        }
    }
}
