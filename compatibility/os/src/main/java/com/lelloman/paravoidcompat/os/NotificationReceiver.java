package com.lelloman.paravoidcompat.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NotificationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationProbe.delivered(context, intent, "action");
    }
}
