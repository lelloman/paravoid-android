package com.lelloman.paravoidcompat.minification;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("probe", MODE_PRIVATE).edit().putBoolean("applicationStarted", true).apply();
    }
}
