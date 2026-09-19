package com.lelloman.paravoidandroid.sample;

import android.content.Context;
import dalvik.system.InMemoryDexClassLoader;

final class ComponentProbe {
    static void record(Context context, Class<?> component, String kind, String request) {
        context.getSharedPreferences("components", Context.MODE_PRIVATE).edit()
            .putString(kind, request)
            .putBoolean(kind + "Payload", component.getClassLoader() instanceof InMemoryDexClassLoader)
            .putInt(kind + "ApplicationCount", SampleApplication.initializationCount).commit();
    }
}
