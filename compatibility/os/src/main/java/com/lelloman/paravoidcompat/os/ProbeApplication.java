package com.lelloman.paravoidcompat.os;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean activityCreated;
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("os-probe", MODE_PRIVATE).edit()
            .putInt("startupPid", android.os.Process.myPid()).commit();
    }
}
