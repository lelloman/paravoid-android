package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps the existing shell WebSocket alive without loading the payload. */
public final class PushForegroundService extends Service {
    private static final String CHANNEL="paravoid-background-push";
    private static final String STOP="com.lelloman.paravoidandroid.STOP_BACKGROUND_PUSH";
    private static final int NOTIFICATION=0x505650;
    private boolean destroyed, promoted;

    static void startConfigured(Activity activity) {
        if(BackgroundPushPreference.read(activity)) start(activity,false);
    }
    static boolean available(Context context) {
        try {
            Bundle metadata=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
            return metadata!=null && metadata.getBoolean("paravoid.backgroundPush",false);
        } catch(PackageManager.NameNotFoundException unavailable) { return false; }
    }
    static boolean start(Activity activity,boolean explicit) {
        boolean previous=BackgroundPushPreference.read(activity);
        try {
            if(!available(activity)) return false;
            if(explicit && !BackgroundPushPreference.write(activity,true)) return false;
            activity.startForegroundService(new Intent(activity,PushForegroundService.class));
            return true;
        } catch(RuntimeException unavailable) {
            if(explicit) BackgroundPushPreference.write(activity,previous);
            android.util.Log.w("ParavoidAndroid","Background update connection could not start");
            return false;
        }
    }
    static boolean pause(Context context) {
        if(!BackgroundPushPreference.write(context,false)) return false;
        context.stopService(new Intent(context,PushForegroundService.class));
        return true;
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Background app updates",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,PushForegroundService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,LauncherActivity.class)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("App update connection active")
            .setContentText("Listening for updates in the background")
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop background connection",stop).build()).build();
        try {
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION,notification);
            promoted=true;
        } catch(RuntimeException unavailable) { stopSelf(); }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) { pause(this); return START_NOT_STICKY; }
        if(!promoted || !BackgroundPushPreference.read(this)) { stopSelf(); return START_NOT_STICKY; }
        // Promote immediately; APK verification and engine initialization run asynchronously.
        UpdateRuntime.engine(this).whenComplete((engine,failure)->new Handler(Looper.getMainLooper()).post(()-> {
            if(destroyed) return;
            if(failure!=null || !UpdateRuntime.backgroundAllowed() || !BackgroundPushPreference.read(this)) { stopSelf(); return; }
            UpdateRuntime.background(this,true);
        }));
        // Avoid respawning the update owner while the restart coordinator stops app processes.
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed=true;
        UpdateRuntime.background(this,false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
