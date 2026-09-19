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
        if (name.equals(LauncherActivity.class.getName())) return super.instantiateActivity(loader, name, intent);
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateActivity(payloadLoader(intent), name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateService(payloadLoader(intent), name, intent);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateReceiver(payloadLoader(intent), name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return ShellApplication.requireInstance().requireComponentFactory()
            .instantiateProvider(payloadLoader(null), name);
    }
}
