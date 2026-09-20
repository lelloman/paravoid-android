package com.lelloman.paravoidcompat.storage;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import androidx.work.*;
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

/** No Hilt and no manual initialize call: lazy configuration must reach this object. */
public final class WorkApplication extends ParavoidAndroidApplication implements Configuration.Provider {
    private boolean ready;
    private int configurationCalls;
    @Override public void onCreate() { super.onCreate(); ready = true; }
    @Override public Configuration getWorkManagerConfiguration() {
        if (!ready || ++configurationCalls != 1) throw new IllegalStateException("Configuration lifecycle mismatch");
        getSharedPreferences("storage-probe", MODE_PRIVATE).edit().putInt("configurationPid", Process.myPid()).commit();
        return new Configuration.Builder().setWorkerFactory(new WorkerFactory() {
            @Override public ListenableWorker createWorker(Context context, String name, WorkerParameters parameters) {
                if (!name.equals(ProbeWorker.class.getName())) return null;
                if (!(context instanceof Application) || context != getApplicationContext()) {
                    throw new IllegalStateException("WorkManager lost the real Application context");
                }
                if (getSharedPreferences("storage-probe", MODE_PRIVATE).getInt("activityPid", 0) == Process.myPid()) {
                    throw new IllegalStateException("Expected cold worker without Activity");
                }
                getSharedPreferences("storage-probe", MODE_PRIVATE).edit().putInt("factoryPid", Process.myPid()).commit();
                return new ProbeWorker(context, parameters);
            }
        }).build();
    }
}
