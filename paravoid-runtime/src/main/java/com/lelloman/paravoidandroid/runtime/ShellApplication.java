package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Build;
import java.nio.ByteBuffer;
import dalvik.system.InMemoryDexClassLoader;

/** Initializes the embedded payload before any Activity, including restored Activities. */
public final class ShellApplication extends Application {
    private static ShellApplication instance;
    private ClassLoader payloadLoader;
    private PayloadApplication application;
    private Throwable failure;
    private android.os.Bundle metadata;

    static ShellApplication requireInstance() {
        if (instance == null) throw new IllegalStateException("Shell Application is not attached.");
        return instance;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            ModuleBundle bundle = ModuleBundle.read(getAssets().open("paravoid/module.zip"), Build.VERSION.SDK_INT);
            payloadLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(bundle.dex), super.getClassLoader());
            metadata = getPackageManager().getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA).metaData;
            String name = metadata.getString("paravoid.application");
            if (name != null) {
                Class<?> type = payloadLoader.loadClass(name);
                if (type.getClassLoader() != payloadLoader) throw new IllegalStateException("Application leaked into shell.");
                application = type.asSubclass(PayloadApplication.class).getConstructor().newInstance();
                application.onCreate();
            }
        } catch (Exception | LinkageError error) {
            failure = error;
            android.util.Log.e("ParavoidAndroid", "Payload initialization failed", error);
        }
    }

    public ClassLoader requirePayloadLoader() {
        if (failure != null) throw new IllegalStateException("Payload initialization failed", failure);
        if (payloadLoader == null) throw new IllegalStateException("Payload is not ready.");
        return payloadLoader;
    }

    public String getPayloadActivity() { return metadata.getString("paravoid.activity"); }

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
