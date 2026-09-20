package com.lelloman.paravoidcompat.os;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;

/** Fixture-only alarm observations. Receiver delivery must not repair its own extras loader. */
public final class AlarmReceiver extends BroadcastReceiver {
    static PendingIntent token(Context context, String run, String kind, String value) {
        Intent intent = new Intent(context, AlarmReceiver.class)
            .setData(Uri.parse("paravoid-alarm://" + run + "/" + kind))
            .putExtra("run", run).putExtra("kind", kind)
            .putExtra("parcel", new ProbeParcel(value));
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // The denied branch intentionally calls the API to prove SecurityException enforcement.
    @SuppressLint("ScheduleExactAlarm")
    static void schedule(Context context, String run) {
        JSONObject result = new JSONObject();
        try {
            AlarmManager manager = context.getSystemService(AlarmManager.class);
            boolean allowed = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms();
            long now = SystemClock.elapsedRealtime();
            result.put("run", run).put("pid", android.os.Process.myPid())
                .put("allowed", allowed).put("start", now);
            if (!allowed) {
                boolean rejected = false;
                PendingIntent denied = token(context, run, "denied", "denied");
                try { manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 15000, denied); }
                catch (SecurityException expected) { rejected = true; }
                finally { manager.cancel(denied); denied.cancel(); }
                result.put("rejected", rejected);
            } else {
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 15000,
                    token(context, run, "cold", "cold-λ-" + run));
                PendingIntent original = token(context, run, "replacement", "obsolete");
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 15000, original);
                PendingIntent replacement = token(context, run, "replacement", "replacement-λ-" + run);
                result.put("sameToken", original.equals(replacement));
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 20000, replacement);
                PendingIntent cancelled = token(context, run, "cancelled", "cancelled");
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 15000, cancelled);
                manager.cancel(cancelled);
                // A later real alarm bounds the cancellation observation; it is not a fake dispatch.
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 30000,
                    token(context, run, "sentinel", "sentinel-λ-" + run));
            }
        } catch (Exception error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        context.getSharedPreferences("os-probe", Context.MODE_PRIVATE).edit()
            .putString("alarmScheduled", result.toString()).commit();
    }

    @Override public void onReceive(Context context, Intent intent) {
        JSONObject result = new JSONObject();
        String kind = "error";
        try {
            kind = intent.getStringExtra("kind");
            String run = intent.getStringExtra("run");
            ProbeParcel parcel = intent.getParcelableExtra("parcel");
            boolean memory = getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader;
            result.put("run", run).put("pid", android.os.Process.myPid())
                .put("at", SystemClock.elapsedRealtime())
                .put("idle", context.getSystemService(android.os.PowerManager.class).isDeviceIdleMode())
                .put("noActivity", !ProbeApplication.activityCreated)
                .put("boot", android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1))
                .put("parcel", parcel != null && (kind + "-λ-" + run).equals(parcel.value))
                .put("loader", memory == context.getPackageName().endsWith(".paravoid"));
        } catch (Exception error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("os-probe", Context.MODE_PRIVATE);
        prefs.edit().putString("alarm_" + kind, result.toString())
            .putInt("alarmCount_" + kind, prefs.getInt("alarmCount_" + kind, 0) + 1).commit();
    }
}
