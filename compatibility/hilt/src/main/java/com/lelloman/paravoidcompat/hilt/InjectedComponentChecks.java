package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import dalvik.system.InMemoryDexClassLoader;

final class InjectedComponentChecks {
    static void check(Context context, Class<?> component, ProbeService singleton) {
        if (singleton == null || singleton != ProbeApplication.initializedService ||
                singleton != ProbeStartupInitializer.service || ProbeApplication.initializationCount != 1) {
            throw new IllegalStateException("Injected component must share the initialized singleton graph");
        }
        if (singleton.context != context.getApplicationContext() || singleton.application != context.getApplicationContext()) {
            throw new IllegalStateException("Injected Android context/Application identity changed");
        }
        if ((component.getClassLoader() instanceof InMemoryDexClassLoader) != context.getPackageName().endsWith(".paravoid")) {
            throw new IllegalStateException("Wrong injected component loader");
        }
    }

    static void record(Context context, String key, String run) {
        if (run == null || !context.getSharedPreferences("hilt-probe", Context.MODE_PRIVATE)
                .edit().putString(key, run).commit()) throw new IllegalStateException("Cannot save component result");
    }
}
