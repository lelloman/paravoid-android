package com.lelloman.paravoidcompat.hilt;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;
import dagger.hilt.android.HiltAndroidApp;
import javax.inject.Inject;

@HiltAndroidApp
public final class ProbeApplication extends ParavoidAndroidApplication {
    @Inject ProbeService service;
    public static ProbeService initializedService;
    public static int initializationCount;

    @Override public void onCreate() {
        super.onCreate();
        if (service == null) throw new IllegalStateException("Application was not injected");
        if (ProbeStartupInitializer.initializationCount != 1 || ProbeStartupInitializer.service != service) {
            throw new IllegalStateException("Startup provider and Application must share one Hilt graph");
        }
        initializedService = service;
        initializationCount++;
    }
}
