package com.lelloman.paravoidcompat.complete;

public final class ProbeApplication extends com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication {
    public static boolean ready;
    @Override public void onCreate() {
        super.onCreate();
        if (!ProbeProvider.created) throw new IllegalStateException("Provider must initialize before Application.onCreate");
        if (getString(R.string.generation).equals("broken")) throw new IllegalStateException("Injected startup failure");
        ready = true;
        getSharedPreferences("probe", 0).edit().putString("application", getString(R.string.generation)).commit();
    }
}
