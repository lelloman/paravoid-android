package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import dalvik.system.InMemoryDexClassLoader;
import dagger.hilt.internal.GeneratedComponentManager;

/** Cold-process shell assertions, running without an instrumentation parent classloader. */
public final class ProbeDeviceChecks {
    private static ProbeViewModel firstModel;
    private static ActivityToken firstToken;
    private ProbeDeviceChecks() {}

    static void check(ProbeActivity activity) {
        require(ProbeApplication.initializationCount == 1, "Application initialized exactly once");
        require(activity.service == ProbeApplication.initializedService, "Application/Activity singleton");
        require(activity.model.service == activity.service, "ViewModel singleton");
        require(activity.service.context == activity.getApplicationContext(), "real application context");
        require(activity.service.application == activity.getApplication(), "real Application binding");
        require(!(activity.getApplication() instanceof GeneratedComponentManager), "shell has no Hilt interface");
        ClassLoader payload = activity.getClass().getClassLoader();
        require(payload instanceof InMemoryDexClassLoader, "Activity comes from payload DEX");
        require(ProbeApplication.class.getClassLoader() == payload, "Application comes from payload DEX");
        require(GeneratedComponentManager.class.getClassLoader() == payload, "Hilt interface comes from payload DEX");
        require(dagger.hilt.android.internal.managers.ActivityComponentManager.class.getClassLoader() == payload,
            "Hilt implementation comes from payload DEX");
        try {
            activity.getApplication().getClass().getClassLoader().loadClass(GeneratedComponentManager.class.getName());
            throw new IllegalStateException("Hilt leaked into the shell classloader");
        } catch (ClassNotFoundException expected) { }

        if (firstModel == null) {
            firstModel = activity.model;
            firstToken = activity.token;
            activity.model.state.set("count", 7);
            activity.getWindow().getDecorView().post(activity::recreate);
        } else {
            require(firstModel == activity.model, "ViewModel retained across recreation");
            require(firstToken != activity.token, "new Activity scope after recreation");
            require(Integer.valueOf(7).equals(activity.model.state.get("count")), "SavedStateHandle retained");
            boolean saved = activity.getSharedPreferences("hilt-probe", Context.MODE_PRIVATE).edit()
                .putString("run", activity.getIntent().getStringExtra("probeRun")).putBoolean("passed", true).commit();
            require(saved, "test result persisted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Hilt shell probe failed: " + message);
    }
}
