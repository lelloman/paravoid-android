package com.lelloman.paravoidcompat.automaticresources;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean created;
    static String constructorJava, applicationJava;
    static String constructorTitle = "not-attached", applicationTitle;
    static final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    @Override public void onCreate() {
        super.onCreate();
        applicationJava = JavaProbe.verify();
        applicationTitle = ProbeActivity.title(this);
        created = true;
        started.countDown();
    }
    public ProbeApplication() {
        constructorJava = JavaProbe.verify();
        if (getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader)
            constructorTitle = ProbeActivity.title(this);
    }
}
