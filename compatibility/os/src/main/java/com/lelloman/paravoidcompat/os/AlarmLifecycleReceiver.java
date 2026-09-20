package com.lelloman.paravoidcompat.os;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;

/** Persisted desired schedule belongs to the downstream app, not the shell runtime. */
public final class AlarmLifecycleReceiver extends BroadcastReceiver {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("os-probe", Context.MODE_PRIVATE);
    }

    private static JSONObject event(Context context, String run) throws Exception {
        return new JSONObject().put("run", run).put("pid", android.os.Process.myPid())
            .put("at", SystemClock.elapsedRealtime()).put("noActivity", !ProbeApplication.activityCreated)
            .put("boot", android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1));
    }

    private static void schedule(Context context, String run, String kind, long when, boolean idle) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
            throw new IllegalStateException("Exact alarm access missing");
        android.app.PendingIntent operation = AlarmReceiver.token(context, run, kind, kind + "-λ-" + run);
        if (idle) manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, operation);
        else manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, operation);
    }

    static void start(Context context, String run, String mode) {
        try {
            long now = SystemClock.elapsedRealtime();
            long wallDue = System.currentTimeMillis() + 45000;
            prefs(context).edit().putString("desiredRun", run).putString("desiredMode", mode)
                .putLong("desiredWallDue", wallDue).commit();
            if ("revoke".equals(mode)) schedule(context, run, "revoked", now + 20000, false);
            else if ("idle".equals(mode)) {
                schedule(context, run, "ordinary", now + 20000, false);
                schedule(context, run, "idle", now + 25000, true);
            } else if ("boot".equals(mode)) schedule(context, run, "beforeBoot", now + 45000, false);
            else throw new IllegalArgumentException(mode);
            prefs(context).edit().putString("lifecycleScheduled", event(context, run)
                .put("mode", mode).put("wallDue", wallDue).toString()).commit();
        } catch (Exception error) { fail(context, error); }
    }

    @Override public void onReceive(Context context, Intent intent) {
        try {
            String mode = prefs(context).getString("desiredMode", "");
            String run = prefs(context).getString("desiredRun", "");
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && "boot".equals(mode);
            boolean grant = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(intent.getAction())
                && "revoke".equals(mode);
            if (!boot && !grant) return;
            AlarmManager manager = context.getSystemService(AlarmManager.class);
            if (android.os.Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return;
            long delay = boot ? Math.max(10000, prefs(context).getLong("desiredWallDue", 0)
                - System.currentTimeMillis()) : 10000;
            String kind = boot ? "afterBoot" : "recovered";
            schedule(context, run, kind, SystemClock.elapsedRealtime() + delay, false);
            prefs(context).edit().putString("lifecycleRecovered", event(context, run)
                .put("action", intent.getAction()).put("kind", kind).put("delay", delay).toString()).commit();
        } catch (Exception error) { fail(context, error); }
    }

    private static void fail(Context context, Exception error) {
        prefs(context).edit().putString("lifecycleError", error.toString()).commit();
    }
}
