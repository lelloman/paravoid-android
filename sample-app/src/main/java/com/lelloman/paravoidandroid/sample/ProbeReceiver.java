package com.lelloman.paravoidandroid.sample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ComponentProbe.record(context, getClass(), "receiver", intent.getStringExtra("request"));
    }
}
