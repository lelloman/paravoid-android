package com.lelloman.paravoidcompat.complete;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public class ProbeService extends Service {
    public static final class Worker extends ProbeService {}
    public IBinder onBind(Intent intent) { return null; }
    public int onStartCommand(Intent intent, int flags, int id) {
        getSharedPreferences("probe", 0).edit().putString("service", getString(R.string.generation)).commit();
        if ("hold".equals(intent.getAction())) return START_NOT_STICKY;
        stopSelf(id); return START_NOT_STICKY;
    }
}
