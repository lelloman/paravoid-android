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
    private CompleteRuntime complete;
    private CrashRecovery crashRecovery;
    private AppComponentFactory componentFactory = new AppComponentFactory();

    void suspendUpdates() { if(complete!=null) complete.suspendUpdates(); }
    void resumeUpdates() { if(complete!=null) complete.resumeUpdates(); }
    boolean pendingUpdateMatches(com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive offer) {
        if(complete==null) return false;
        try { return offer.equals(complete.lifecycle.snapshot().pending); }
        catch(com.lelloman.paravoidandroid.contract.ContractException unavailable) { return false; }
    }
    boolean isPushComponent(String name) {
        if(metadata==null) return false;
        return java.util.Arrays.asList(metadata.getString("paravoid.pushComponents","").split(";")).contains(name);
    }
    static ShellApplication requireInstance() {
        if (instance == null) throw new IllegalStateException("Shell Application is not attached.");
        return instance;
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
        try {
            metadata = getPackageManager().getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA).metaData;
            if (metadata == null) metadata = new android.os.Bundle();
            if (metadata.getBoolean("paravoid.complete", false)) {
                if (Application.getProcessName().equals(getPackageName() + UpdateRuntime.SUFFIX)) return;
                if (metadata.getBoolean("paravoid.crashRecovery", false))
                    crashRecovery = new CrashRecovery(this, metadata.getString("paravoid.recoveryProvider", ""));
                if (Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Complete packaging requires API 30");
                complete = new CompleteRuntime(this);
                if (crashRecovery != null) crashRecovery.runtime = complete;
                if (complete.shellOnly) return;
                payloadLoader = complete.load(super.getClassLoader());
            } else {
                EmbeddedResources.install(this);
                ModuleBundle bundle = ModuleBundle.read(getAssets().open("paravoid/module.zip"), Build.VERSION.SDK_INT);
                payloadLoader = bundle.createClassLoader(super.getClassLoader(),
                    EmbeddedNativeLibraries.path(this),
                    EmbeddedArchive.materialize(this, "java-resources", ".jar"));
            }
            // Library discovery defaults to this loader; newly created threads inherit it.
            // Set it before user constructors and provider initialization, not just onCreate.
            Thread.currentThread().setContextClassLoader(payloadLoader);
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
            if (!(error instanceof CrashRecovery.Required)) {
                if (crashRecovery != null) crashRecovery.capture(Thread.currentThread(), error);
                if (complete != null) complete.failed();
            }
            failure = error;
            android.util.Log.e("ParavoidAndroid", "Payload initialization failed", error);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (failure == null) {
            try {
                if (application != null) application.onCreate();
                if (complete != null && !complete.shellOnly) complete.applicationCreated();
                if (crashRecovery != null) crashRecovery.install();
            } catch (Exception | LinkageError error) {
                if (crashRecovery != null) crashRecovery.capture(Thread.currentThread(), error);
                if (complete != null) complete.failed();
                failure = error;
                android.util.Log.e("ParavoidAndroid", "Payload onCreate failed", error);
                // Providers are already published at this point. Keeping this
                // failed process alive would leave their payload implementations
                // callable despite quarantine. Preserve Android's startup-failure
                // termination; the next entry constructs unavailable adapters.
                if (complete != null) throw new IllegalStateException("Payload Application failed to start", error);
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
    boolean completeUnavailable() {
        return metadata != null && metadata.getBoolean("paravoid.complete", false) && (failure != null || payloadLoader == null);
    }
    boolean isDeclaredJob(String name) {
        try {
            return (Boolean) super.getClassLoader().loadClass("com.lelloman.paravoidandroid.runtime.DeclaredServices")
                .getMethod("isJobService", String.class).invoke(null, name);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Shell service-kind dispatch missing", error); }
    }

    /** Available to providers after attachment; does not imply onCreate has run. */
    public PayloadApplication requirePayloadApplication() {
        if (failure != null) throw new IllegalStateException("Payload initialization failed", failure);
        if (application == null) throw new IllegalStateException("Payload Application is not attached.");
        return application;
    }

    @Override public ClassLoader getClassLoader() {
        return payloadLoader != null ? payloadLoader : super.getClassLoader();
    }
    @Override public Context createConfigurationContext(Configuration configuration) {
        return PayloadContext.wrap(super.createConfigurationContext(configuration), getClassLoader());
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
