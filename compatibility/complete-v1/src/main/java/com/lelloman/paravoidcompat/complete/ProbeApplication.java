package com.lelloman.paravoidcompat.complete;

public final class ProbeApplication extends com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication {
    public static boolean ready;
    public ProbeApplication() {
        // Native paths and the loader must work before the user constructor,
        // including JNI_OnLoad/FindClass and DT_NEEDED dependencies.
        if (com.lelloman.paravoidcompat.jni.bridge.NativeBridge.registered(33) != 42 ||
                com.lelloman.paravoidcompat.jni.bridge.NativeBridge.dynamicLibrary() != 73 ||
                com.lelloman.paravoidcompat.jni.bridge.NativeBridge.callback() != 42)
            throw new IllegalStateException("Native generation unavailable in Application constructor");
    }
    @Override public void onCreate() {
        super.onCreate();
        if (!ProbeProvider.created) throw new IllegalStateException("Provider must initialize before Application.onCreate");
        if (getString(R.string.generation).equals("broken") ||
                (getString(R.string.generation).equals("broken-main") &&
                 android.app.Application.getProcessName().equals(getPackageName())))
            throw new IllegalStateException("Injected startup failure");
        if (getString(R.string.generation).equals("incomplete")) android.os.Process.killProcess(android.os.Process.myPid());
        ready = true;
        getSharedPreferences("probe", 0).edit().putString("application", getString(R.string.generation)).commit();
    }
}
