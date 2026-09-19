package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.os.Bundle;

/** Called by payload bytecode before user/AndroidX Activity initialization. */
public final class PayloadSavedState {
    private PayloadSavedState() {}

    public static void prepare(Activity activity, Bundle state) {
        if (state != null) state.setClassLoader(activity.getClass().getClassLoader());
    }
}
