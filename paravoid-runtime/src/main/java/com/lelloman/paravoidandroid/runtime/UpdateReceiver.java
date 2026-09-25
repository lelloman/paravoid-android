package com.lelloman.paravoidandroid.runtime;

import android.content.*;
import android.os.*;
/** Reconcile after boot/unlock/APK replacement without starting the payload Application. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        PendingResult pending=goAsync();
        UpdateRuntime.engine(context).whenComplete((engine,failure)-> {
            if(engine!=null) engine.foreground();
            new Handler(Looper.getMainLooper()).post(pending::finish);
        });
    }
}
