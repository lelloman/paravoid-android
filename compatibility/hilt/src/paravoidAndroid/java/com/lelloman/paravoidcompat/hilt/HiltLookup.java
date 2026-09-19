package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import com.lelloman.paravoidandroid.runtime.ShellApplication;

/** Payload-only bridge used by the experimental bytecode adapter. */
public final class HiltLookup {
    private HiltLookup() {}

    public static Object manager(Context context) {
        Context application = context.getApplicationContext();
        if (application instanceof ShellApplication) {
            return ((ShellApplication) application).requirePayloadApplication();
        }
        return application;
    }
}
