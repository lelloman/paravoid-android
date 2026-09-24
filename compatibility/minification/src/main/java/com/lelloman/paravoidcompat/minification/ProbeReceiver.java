package com.lelloman.paravoidcompat.minification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("probe", Context.MODE_PRIVATE).edit().putBoolean("receiverStarted", true).apply();
    }
}
