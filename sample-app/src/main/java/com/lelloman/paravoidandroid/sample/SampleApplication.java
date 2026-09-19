package com.lelloman.paravoidandroid.sample;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class SampleApplication extends ParavoidAndroidApplication {
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
