package com.lelloman.paravoidcompat.hilt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class InjectedReceiver extends BroadcastReceiver {
    @Inject ProbeService singleton;

    @Override public void onReceive(Context context, Intent intent) {
        InjectedComponentChecks.check(context, getClass(), singleton);
        InjectedComponentChecks.record(context, "injectedReceiverRun", intent.getStringExtra("probeRun"));
    }
}
