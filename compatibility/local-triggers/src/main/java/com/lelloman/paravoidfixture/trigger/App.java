package com.lelloman.paravoidfixture.trigger;
public final class App extends com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication {
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("payload-probe",0).edit().putString("generation",getString(R.string.generation)).commit();
    }
}
