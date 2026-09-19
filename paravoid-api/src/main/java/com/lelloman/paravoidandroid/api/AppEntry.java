package com.lelloman.paravoidandroid.api;

import android.app.Activity;
import android.view.View;

/** Version 1 contract, supplied by the host and referenced compile-only by modules. */
public interface AppEntry {
    int API_VERSION = 1;

    /** Called on the main thread, once for each Activity instance. */
    View createView(Activity activity, String executionMode);
}
