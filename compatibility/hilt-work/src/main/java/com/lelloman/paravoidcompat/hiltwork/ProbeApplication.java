package com.lelloman.paravoidcompat.hiltwork;

import androidx.hilt.work.HiltWorkerFactory;
import androidx.work.Configuration;
import androidx.work.WorkManager;
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;
import dagger.hilt.android.HiltAndroidApp;
import javax.inject.Inject;

@HiltAndroidApp
public final class ProbeApplication extends ParavoidAndroidApplication implements Configuration.Provider {
    @Inject HiltWorkerFactory workerFactory;
    @Inject Repository repository;
    static ProbeApplication current;
    static boolean ready;
    static boolean activityCreated;
    static int configurationCalls;

    @Override public void onCreate() {
        super.onCreate(); // Hilt injection must finish before requesting configuration.
        current = this;
        if (BuildConfig.EXPLICIT_INITIALIZATION) {
            WorkManager.initialize(this, getWorkManagerConfiguration());
        }
        ready = true;
    }

    @Override public Configuration getWorkManagerConfiguration() {
        if (workerFactory == null || repository == null) throw new IllegalStateException("Missing Application injection");
        configurationCalls++;
        return new Configuration.Builder().setWorkerFactory(workerFactory).build();
    }
}
