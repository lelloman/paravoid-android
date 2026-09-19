package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** Prepares payload classes before providers; delivers onCreate at Android's normal time. */
public final class ShellApplication extends Application {
    private static ShellApplication instance;
    private ClassLoader payloadLoader;
    private PayloadApplication application;
    private Throwable failure;
    private android.os.Bundle metadata;
    private AppComponentFactory componentFactory = new AppComponentFactory();

    static ShellApplication requireInstance() {
        if (instance == null) throw new IllegalStateException("Shell Application is not attached.");
        return instance;
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
        try {
            ModuleBundle bundle = ModuleBundle.read(getAssets().open("paravoid/module.zip"), Build.VERSION.SDK_INT);
            payloadLoader = bundle.createClassLoader(super.getClassLoader());
            metadata = getPackageManager().getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA).metaData;
            String name = metadata.getString("paravoid.application");
            if (name != null) {
                Class<?> type = payloadLoader.loadClass(name);
                if (type.getClassLoader() != payloadLoader) throw new IllegalStateException("Application leaked into shell.");
                application = type.asSubclass(PayloadApplication.class).getConstructor().newInstance();
            }
            String factory = metadata.getString("paravoid.componentFactory");
            if (factory != null) {
                componentFactory = payloadLoader.loadClass(factory).asSubclass(AppComponentFactory.class)
                    .getConstructor().newInstance();
            }
        } catch (Exception | LinkageError error) {
            failure = error;
            android.util.Log.e("ParavoidAndroid", "Payload initialization failed", error);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (failure == null && application != null) {
            try {
                application.onCreate();
            } catch (Exception | LinkageError error) {
                failure = error;
                android.util.Log.e("ParavoidAndroid", "Payload onCreate failed", error);
            }
        }
    }

    AppComponentFactory requireComponentFactory() {
        requirePayloadLoader();
        return componentFactory;
    }

    public ClassLoader requirePayloadLoader() {
        if (failure != null) throw new IllegalStateException("Payload initialization failed", failure);
        if (payloadLoader == null) throw new IllegalStateException("Payload is not ready.");
        return payloadLoader;
    }

    public String getPayloadActivity() { return metadata.getString("paravoid.activity"); }

    /** Available to providers after attachment; does not imply onCreate has run. */
    public PayloadApplication requirePayloadApplication() {
        if (failure != null) throw new IllegalStateException("Payload initialization failed", failure);
        if (application == null) throw new IllegalStateException("Payload Application is not attached.");
        return application;
    }

    @Override public ClassLoader getClassLoader() {
        return payloadLoader != null ? payloadLoader : super.getClassLoader();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (application != null && failure == null) application.onConfigurationChanged(configuration);
    }
    @Override public void onLowMemory() {
        super.onLowMemory();
        if (application != null && failure == null) application.onLowMemory();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (application != null && failure == null) application.onTrimMemory(level);
    }
    @Override public void onTerminate() {
        if (application != null && failure == null) application.onTerminate();
        super.onTerminate();
    }
}
