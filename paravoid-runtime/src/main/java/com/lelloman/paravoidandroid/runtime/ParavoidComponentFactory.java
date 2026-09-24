package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/** Android attaches/manages payload components; supported factory wrapping stays intact. */
public final class ParavoidComponentFactory extends AppComponentFactory {
    private static ClassLoader payloadLoader(Intent intent) {
        ClassLoader loader = ShellApplication.requireInstance().requirePayloadLoader();
        if (intent != null) intent.setExtrasClassLoader(loader);
        return loader;
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (name.equals("com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity") && CrashRecovery.instance != null)
            return new CrashRecoveryActivity();
        if (name.equals(CrashRecoveryActivity.class.getName()) || name.equals(LauncherActivity.class.getName()) || name.equals("com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity"))
            return super.instantiateActivity(loader, name, intent);
        if (ShellApplication.requireInstance().completeUnavailable()) return new UnavailableComponents.Screen();
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateActivity(payloadLoader(intent), name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (ShellApplication.requireInstance().completeUnavailable()) return ShellApplication.requireInstance().isDeclaredJob(name)
            ? new UnavailableComponents.Job() : new UnavailableComponents.StartedOrBound(name);
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateService(payloadLoader(intent), name, intent);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (ShellApplication.requireInstance().completeUnavailable()) return new UnavailableComponents.Receiver();
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateReceiver(payloadLoader(intent), name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (ShellApplication.requireInstance().completeUnavailable()) return new UnavailableComponents.Provider();
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateProvider(payloadLoader(null), name);
    }
}
