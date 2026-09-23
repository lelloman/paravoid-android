package com.lelloman.paravoidcompat.complete;
import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.IBinder;
public class ProbeService extends Service {
    public static final class Worker extends ProbeService {}
    public static final class Foreground extends ProbeService {}
    public IBinder onBind(Intent intent) { return null; }
    public int onStartCommand(Intent intent, int flags, int id) {
        if (this instanceof Worker && (intent == null || "sticky".equals(intent.getAction()))) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("sticky-probe", "Sticky worker probe", NotificationManager.IMPORTANCE_LOW));
            startForeground(73, new Notification.Builder(this, "sticky-probe")
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Paravoid sticky worker probe").build());
            getSharedPreferences("sticky-worker", 0).edit()
                .putInt("pid", android.os.Process.myPid()).putBoolean("restarted", intent == null)
                .putString("generation", getString(R.string.generation)).commit();
            return START_STICKY;
        }
        getSharedPreferences("probe", 0).edit().putString("service", getString(R.string.generation)).commit();
        if (intent != null && "hold".equals(intent.getAction())) return START_NOT_STICKY;
        stopSelf(id); return START_NOT_STICKY;
    }
}
