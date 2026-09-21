package com.lelloman.paravoidcompat.resourcesplit;

import android.content.Context;
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;
import com.lelloman.paravoidandroid.runtime.SplitResources;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean created;
    static String constructorTitle = "not-attached";
    public ProbeApplication() {
        // A normal Android Application is not attached during its constructor.
        // The transformed shell-backed object is: verify the earlier hook there.
        if (getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader)
            constructorTitle = ProbeActivity.title(this);
    }
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SplitResources.install(this); // Normal control only; shell uses its early hook.
    }
    @Override public void onCreate() {
        super.onCreate();
        created = true;
        getSharedPreferences("split-probe", MODE_PRIVATE).edit()
            .putString("applicationTitle", ProbeActivity.title(this)).commit();
    }
}
