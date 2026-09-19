package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.content.Intent;

/** Android attaches and manages the actual payload Activity returned by this factory. */
public final class ParavoidComponentFactory extends AppComponentFactory {
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!name.equals(LauncherActivity.class.getName())) {
            loader = ShellApplication.requireInstance().requirePayloadLoader();
            if (intent != null) intent.setExtrasClassLoader(loader);
        }
        return super.instantiateActivity(loader, name, intent);
    }
}
