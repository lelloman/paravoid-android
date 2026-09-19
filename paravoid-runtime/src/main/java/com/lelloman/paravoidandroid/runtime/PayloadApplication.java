package com.lelloman.paravoidandroid.runtime;

import android.content.ContextWrapper;
import android.content.res.Configuration;

/** Runtime target of the Application transformation; deliberately not an Application. */
public class PayloadApplication extends ContextWrapper {
    public PayloadApplication() { super(ShellApplication.requireInstance()); }
    public void onCreate() {}
    public void onTerminate() {}
    public void onLowMemory() {}
    public void onTrimMemory(int level) {}
    public void onConfigurationChanged(Configuration configuration) {}
}
