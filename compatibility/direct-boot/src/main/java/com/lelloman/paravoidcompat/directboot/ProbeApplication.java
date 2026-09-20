package com.lelloman.paravoidcompat.directboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserManager;
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication;

public final class ProbeApplication extends ParavoidAndroidApplication {
    static boolean activityCreated;
    @Override public void onCreate() {
        super.onCreate();
        BootReceiver.applicationStarted(this);
        if (getSystemService(UserManager.class).isUserUnlocked()) {
            BootReceiver.initializeUnlocked(this, "application");
        } else {
            BroadcastReceiver unlock = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    BootReceiver.initializeUnlocked(context, "userUnlocked");
                    unregisterReceiver(this);
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlock, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(unlock, filter);
        }
    }
}
