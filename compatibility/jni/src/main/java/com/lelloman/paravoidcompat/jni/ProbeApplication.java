package com.lelloman.paravoidcompat.jni;

import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;
import com.lelloman.paravoidcompat.jni.bridge.NativeBridge;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean created;
    static String result = "not run";
    @Override public void onCreate() {
        super.onCreate();
        result = ProbeProvider.probe(() -> {
            if (!"PASS".equals(ProbeProvider.result) || NativeBridge.onLoadCount() != 1
                    || NativeBridge.callback() != 42) throw new AssertionError("Application JNI startup");
        });
        created = true;
    }
}
