package com.lelloman.paravoidcompat.complete;
import android.content.*;
public final class ProbeReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("probe", 0).edit().putString("receiver", context.getString(R.string.generation)).commit();
    }
}
