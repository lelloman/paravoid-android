package com.lelloman.paravoidcompat.os;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("os-probe", MODE_PRIVATE).edit()
            .putInt("startupPid", android.os.Process.myPid()).commit();
    }
}
