package com.lelloman.paravoidcompat.os;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;

final class NotificationProbe {
    private static final String CHANNEL = "probe";
    private static final int ID = 41;

    static void post(Activity activity, String run) {
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Compatibility probe",
                NotificationManager.IMPORTANCE_DEFAULT));
            Intent content = new Intent(activity, ProbeActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("scenario", "notificationDelivery").putExtra("probeRun", run)
                .putExtra("parcel", new ProbeParcel("content-λ-" + run));
            PendingIntent contentToken = PendingIntent.getActivity(activity, 41, content,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent action = new Intent(activity, NotificationReceiver.class)
                .putExtra("probeRun", run).putExtra("parcel", new ProbeParcel("action-λ-" + run));
            PendingIntent actionToken = PendingIntent.getBroadcast(activity, 42, action,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(activity, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Paravoid " + run).setContentText("Cold PendingIntent probe")
                .setContentIntent(contentToken).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Probe action", actionToken).build())
                .build();
            manager.notify(ID, notification);
            verifyPosted(activity, run, contentToken, actionToken, 0);
        } catch (Exception error) {
            saveError(activity, "posted", error);
        }
    }

    private static void verifyPosted(Activity activity, String run, PendingIntent contentToken,
            PendingIntent actionToken, int attempt) {
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            Notification posted = null;
            for (StatusBarNotification entry : manager.getActiveNotifications()) {
                if (entry.getId() == ID) posted = entry.getNotification();
            }
            if (posted == null) {
                if (attempt >= 50) throw new IllegalStateException("Notification not posted (permission/channel?)");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> verifyPosted(activity, run, contentToken, actionToken, attempt + 1), 100);
                return;
            }
            JSONObject result = new JSONObject().put("run", run)
                .put("enabled", manager.areNotificationsEnabled())
                .put("channel", CHANNEL.equals(posted.getChannelId()))
                .put("contentToken", contentToken.equals(posted.contentIntent))
                .put("actionToken", actionToken.equals(posted.actions[0].actionIntent));
            save(activity, "posted", result);
            // Pass the actual published action token, not a reconstructed Intent.
            activity.startActivity(new Intent().setClassName("com.lelloman.paravoidcompat.os.peer",
                "com.lelloman.paravoidcompat.os.peer.PeerActivity")
                .putExtra("scenario", "pending").putExtra("run", run)
                .putExtra("pending", posted.actions[0].actionIntent));
        } catch (Exception error) {
            saveError(activity, "posted", error);
        }
    }

    static void delivered(Context context, Intent intent, String kind) {
        try {
            String run = intent.getStringExtra("probeRun");
            // Deliberately no manual setExtrasClassLoader: exercise production loading.
            ProbeParcel parcel = intent.getParcelableExtra("parcel");
            JSONObject result = new JSONObject().put("run", run)
                .put("pid", android.os.Process.myPid())
                .put("parcel", parcel != null && (kind + "-λ-" + run).equals(parcel.value))
                .put("loader", parcel != null && parcel.getClass().getClassLoader() == NotificationProbe.class.getClassLoader())
                .put("immutable", !intent.hasExtra("injected"));
            save(context, kind, result);
        } catch (Exception error) { saveError(context, kind, error); }
    }

    private static void save(Context context, String key, JSONObject result) {
        context.getSharedPreferences("os-probe", Context.MODE_PRIVATE).edit()
            .putString(key, result.toString()).commit();
    }
    private static void saveError(Context context, String key, Exception error) {
        try { save(context, key, new JSONObject().put("error", error.toString())); }
        catch (Exception ignored) { throw new RuntimeException(error); }
    }
}
