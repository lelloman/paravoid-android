package com.lelloman.paravoidcompat.os;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcel;
import com.lelloman.paravoidandroid.runtime.PayloadSavedState;

/** Simulates framework reads with a loader that cannot see the app's classes. */
final class SavedStateEnvelopeProbe {
    static boolean check(Activity activity) {
        Bundle state = new Bundle();
        state.putParcelable("value", new ProbeParcel("envelope-λ"));
        state.putBoolean("frameworkFlag", false);
        PayloadSavedState.protect(activity, state);
        state.putString("subclassAdded", "after-super");
        PayloadSavedState.protect(activity, state); // Nested callback protection.
        state.putBoolean("frameworkFlag", true); // Framework writes after callback.
        Parcel wire = Parcel.obtain();
        try {
            wire.writeBundle(state);
            wire.setDataPosition(0);
            Bundle restored = wire.readBundle(activity.getClass().getClassLoader().getParent());
            if (!restored.getBoolean("frameworkFlag")) return false;
            PayloadSavedState.prepare(activity, restored);
            PayloadSavedState.prepare(activity, restored); // Repeated preparation is safe.
            ProbeParcel value = restored.getParcelable("value");
            return value != null && "envelope-λ".equals(value.value)
                && "after-super".equals(restored.getString("subclassAdded"))
                && restored.getBoolean("frameworkFlag") && restored.size() == 3;
        } finally { wire.recycle(); }
    }
}
