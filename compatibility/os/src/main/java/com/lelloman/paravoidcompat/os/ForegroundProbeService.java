package com.lelloman.paravoidcompat.os;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.service.notification.StatusBarNotification;
import com.lelloman.paravoidcompat.os.contract.WireMessage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Synthetic local-file-processing dataSync fixture, not a production sync engine. */
public final class ForegroundProbeService extends Service {
    private static final int ID = 73;
    private static final String CHANNEL = "foreground-probe";
    private final String instance = UUID.randomUUID().toString();
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            WireMessage parcel = intent.getParcelableExtra("foregroundParcel");
            if (parcel == null) throw new IllegalArgumentException("Missing foreground Parcelable");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Foreground compatibility",
                NotificationManager.IMPORTANCE_LOW));
            Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Foreground " + parcel.text).setContentText("Local processing lifecycle probe");
            if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, builder.build());
            try (FileOutputStream file = new FileOutputStream(new File(getFilesDir(), "foreground-checkpoint.txt"))) {
                file.write(parcel.text.getBytes(StandardCharsets.UTF_8));
            }
            int starts = getSharedPreferences("os-probe", MODE_PRIVATE).getInt("foregroundStarts", 0) + 1;
            getSharedPreferences("os-probe", MODE_PRIVATE).edit().putInt("foregroundStarts", starts).commit();
            JSONObject result = new JSONObject().put("run", parcel.text).put("instance", instance)
                .put("pid", android.os.Process.myPid()).put("starts", starts)
                .put("redelivered", (flags & START_FLAG_REDELIVERY) != 0)
                .put("type", Build.VERSION.SDK_INT < 29 || getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                .put("loader", parcel.getClass().getClassLoader() == getClass().getClassLoader());
            observeNotification(result, 0);
            return START_REDELIVER_INTENT;
        } catch (Exception error) {
            fail(error);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void observeNotification(JSONObject result, int attempt) {
        try {
            for (StatusBarNotification entry : getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (entry.getId() != ID) continue;
                Notification notification = entry.getNotification();
                result.put("notification", CHANNEL.equals(notification.getChannelId())
                    && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
                getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("foreground", result.toString()).commit();
                return;
            }
            if (attempt >= 50) throw new IllegalStateException("Foreground notification absent");
            new Handler(Looper.getMainLooper()).postDelayed(() -> observeNotification(result, attempt + 1), 100);
        } catch (Exception error) { fail(error); }
    }
    @Override public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        observeRemoval(0);
        super.onDestroy();
    }
    private void observeRemoval(int attempt) {
        boolean present = false;
        for (StatusBarNotification entry : getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (entry.getId() == ID) present = true;
        }
        if (present && attempt < 50) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> observeRemoval(attempt + 1), 100);
            return;
        }
        getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("foregroundDestroyed", instance)
            .putBoolean("foregroundNotificationRemoved", !present).commit();
    }
    private void fail(Exception error) {
        getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("foregroundError", error.toString()).commit();
    }
}
