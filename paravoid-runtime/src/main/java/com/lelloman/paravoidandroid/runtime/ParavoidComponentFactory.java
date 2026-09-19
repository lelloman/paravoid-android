package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;

/** Android attaches and manages the actual payload Activity returned by this factory. */
public final class ParavoidComponentFactory extends AppComponentFactory {
    private static ClassLoader payloadLoader(Intent intent) {
        ClassLoader loader = ShellApplication.requireInstance().requirePayloadLoader();
        if (intent != null) intent.setExtrasClassLoader(loader);
        return loader;
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!name.equals(LauncherActivity.class.getName())) {
            loader = payloadLoader(intent);
        }
        return super.instantiateActivity(loader, name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.instantiateService(payloadLoader(intent), name, intent);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.instantiateReceiver(payloadLoader(intent), name, intent);
    }
}
