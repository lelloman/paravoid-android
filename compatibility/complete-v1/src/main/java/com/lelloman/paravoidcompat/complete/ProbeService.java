package com.lelloman.paravoidcompat.complete;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public final class ProbeService extends Service {
    public IBinder onBind(Intent intent) { return null; }
    public int onStartCommand(Intent intent, int flags, int id) {
        getSharedPreferences("probe", 0).edit().putString("service", getString(R.string.generation)).commit();
        stopSelf(id); return START_NOT_STICKY;
    }
}
