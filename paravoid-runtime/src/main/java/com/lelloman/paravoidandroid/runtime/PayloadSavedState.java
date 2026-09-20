package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Called by payload bytecode before user/AndroidX Activity initialization. */
public final class PayloadSavedState {
    private PayloadSavedState() {}

    public static void prepare(Activity activity, Bundle state) {
        ClassLoader loader = activity.getClass().getClassLoader();
        // ActivityThread overwrites both loaders after AppComponentFactory returns.
        // Repair the launch Intent even on a fresh entry with no saved state.
        Intent intent = activity.getIntent();
        if (intent != null) intent.setExtrasClassLoader(loader);
        if (state != null) state.setClassLoader(loader);
    }
}
