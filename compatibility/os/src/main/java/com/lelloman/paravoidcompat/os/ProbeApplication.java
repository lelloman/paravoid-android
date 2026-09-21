package com.lelloman.paravoidcompat.os;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean activityCreated;
    private static int starts;
    @Override public void onCreate() {
        super.onCreate();
        String process = android.app.Application.getProcessName();
        ClassLoader loader = ProbeApplication.class.getClassLoader();
        getSharedPreferences(process.endsWith(":worker") ? "os-worker" : "os-probe", MODE_PRIVATE).edit()
            .putInt("startupPid", android.os.Process.myPid())
            .putInt("startupCount", ++starts).putString("startupProcess", process)
            .putString("startupInstance", java.util.UUID.randomUUID().toString())
            .putBoolean("startupLoader", loader == com.lelloman.paravoidcompat.os.contract.IProbe.class.getClassLoader()
                && (loader instanceof dalvik.system.InMemoryDexClassLoader) == getPackageName().endsWith(".paravoid"))
            .commit();
    }
}
