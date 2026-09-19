package com.lelloman.voidandroid.sample;

import com.lelloman.voidandroid.runtime.VoidAndroidApplication;

public final class SampleApplication extends VoidAndroidApplication {
    public static int initializationCount;
    private final String preferenceName = "startup";

    @Override public void onCreate() {
        super.onCreate();
        initializationCount++;
        // Exercises fields, inherited Context methods, and the real application context.
        getSharedPreferences(preferenceName, MODE_PRIVATE).edit()
            .putString("package", getApplicationContext().getPackageName()).commit();
    }
}
