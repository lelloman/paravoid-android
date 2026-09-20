package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Called by payload bytecode around Activity save/restore callbacks. */
public final class PayloadSavedState {
    private static final String ENVELOPE = "com.lelloman.paravoidandroid.runtime.PAYLOAD_STATE_V1";
    private PayloadSavedState() {}

    public static void prepare(Activity activity, Bundle state) {
        ClassLoader loader = activity.getClass().getClassLoader();
        // ActivityThread overwrites both loaders after AppComponentFactory returns.
        // Repair the launch Intent even on a fresh entry with no saved state.
        Intent intent = activity.getIntent();
        if (intent != null) intent.setExtrasClassLoader(loader);
        if (state != null) unpack(state, loader);
    }

    /** Keep payload values below a lazy platform Bundle boundary during early framework reads. */
    public static void protect(Activity activity, Bundle state) {
        ClassLoader loader = activity.getClass().getClassLoader();
        unpack(state, loader); // Handles nested superclass/persistable callback protection.
        Bundle payload = new Bundle(state);
        state.clear();
        state.putBundle(ENVELOPE, payload);
        // Android may append its own state after onSaveInstanceState returns.
        // Leave those entries at the root so its pre-onCreate reads still work.
    }

    private static void unpack(Bundle state, ClassLoader loader) {
        state.setClassLoader(loader);
        Bundle payload = state.getBundle(ENVELOPE);
        if (payload == null) return; // Old/unwrapped state is still accepted.
        payload.setClassLoader(loader);
        state.remove(ENVELOPE);
        payload.putAll(state); // Later framework writes win over callback state.
        state.clear();
        state.putAll(payload);
    }
}
