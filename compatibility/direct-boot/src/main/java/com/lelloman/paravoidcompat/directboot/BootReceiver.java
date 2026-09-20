package com.lelloman.paravoidcompat.directboot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserManager;
import org.json.JSONObject;

public final class BootReceiver extends BroadcastReceiver {
    private static final String ALARM = "com.lelloman.paravoidcompat.directboot.ALARM";
    private static SharedPreferences device(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("direct-boot", Context.MODE_PRIVATE);
    }
    private static int boot(Context context) {
        return android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
    }
    private static JSONObject event(Context context, String kind) throws Exception {
        boolean memory = BootReceiver.class.getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader;
        return new JSONObject().put("kind", kind).put("app", context.getPackageName())
            .put("run", device(context).getString("run", ""))
            .put("boot", boot(context)).put("pid", android.os.Process.myPid())
            .put("unlocked", context.getSystemService(UserManager.class).isUserUnlocked())
            .put("noActivity", !ProbeApplication.activityCreated)
            .put("loader", memory == context.getPackageName().endsWith(".paravoid"));
    }
    private static synchronized void record(Context context, JSONObject value) throws Exception {
        String kind = value.getString("kind");
        SharedPreferences prefs = device(context);
        // Leaving the stopped state can deliver BOOT_COMPLETED during preparation.
        // Count observations per real boot, not across setup and the subsequent reboot.
        if (prefs.getInt("observationBoot", -1) != boot(context)) {
            SharedPreferences.Editor reset = prefs.edit().putInt("observationBoot", boot(context));
            for (String previous : new String[] {"application", "locked", "alarm", "initialized",
                    "unlockAttempt", "bootCompleted", "error"}) {
                reset.remove(previous).remove(previous + "Count");
            }
            if (!reset.commit()) throw new IllegalStateException("Observation reset failed");
        }
        int count = prefs.getInt(kind + "Count", 0) + 1;
        value.put("count", count);
        if (!prefs.edit().putString(kind, value.toString()).putInt(kind + "Count", count).commit())
            throw new IllegalStateException("Device-protected commit failed");
        android.util.Log.i("DirectBootProbe", value.toString());
    }
    private static void fail(Context context, Exception error) {
        try { record(context, event(context, "error").put("error", error.toString())); }
        catch (Exception nested) { android.util.Log.e("DirectBootProbe", "Observation failed", nested); }
    }
    static void prepare(Context context, String run) {
        try {
            if (run == null) throw new IllegalArgumentException("Missing run token");
            device(context).edit().clear().putString("run", run).putString("deviceValue", "device-λ-" + run).commit();
            context.getSharedPreferences("credential", Context.MODE_PRIVATE).edit()
                .putString("value", "credential-λ-" + run).commit();
            // Exercise initialization in the setup boot too: the next real boot
            // must not mistake these observations for duplicate unlock work.
            initializeUnlocked(context, "preparation");
            record(context, event(context, "prepared"));
        } catch (Exception error) { fail(context, error); }
    }
    static void applicationStarted(Context context) {
        if (!device(context).contains("run")) return;
        try { record(context, event(context, "application")); }
        catch (Exception error) { fail(context, error); }
    }
    static synchronized void initializeUnlocked(Context context, String reason) {
        if (!device(context).contains("run")) return;
        try {
            if (!context.getSystemService(UserManager.class).isUserUnlocked())
                throw new IllegalStateException("Credential initialization while locked");
            SharedPreferences prefs = device(context);
            String run = prefs.getString("run", "");
            boolean credential = ("credential-λ-" + run).equals(
                context.getSharedPreferences("credential", Context.MODE_PRIVATE).getString("value", ""));
            if (!credential) throw new IllegalStateException("Credential data lost");
            if (prefs.getInt("initializedBoot", -1) != boot(context)) {
                prefs.edit().putInt("initializedBoot", boot(context)).commit();
                record(context, event(context, "initialized").put("reason", reason).put("credential", true));
            }
            record(context, event(context, "unlockAttempt").put("reason", reason));
        } catch (Exception error) { fail(context, error); }
    }
    @Override public void onReceive(Context context, Intent intent) {
        try {
            SharedPreferences prefs = device(context);
            String run = prefs.getString("run", "");
            if (run.isEmpty()) return;
            if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
                boolean rejected = false;
                try { context.getSharedPreferences("credential", Context.MODE_PRIVATE).getString("value", ""); }
                catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("Credential storage was accessible before unlock");
                boolean data = ("device-λ-" + run).equals(prefs.getString("deviceValue", ""));
                if (!data) throw new IllegalStateException("Device data lost");
                AlarmManager manager = context.getSystemService(AlarmManager.class);
                if (android.os.Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
                    throw new IllegalStateException("Exact alarm access missing");
                Intent alarm = new Intent(context, BootReceiver.class).setAction(ALARM)
                    .setData(Uri.parse("paravoid-direct-boot://" + run))
                    .putExtra("parcel", new PayloadParcel("alarm-λ-" + run));
                manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10000,
                    PendingIntent.getBroadcast(context, 0, alarm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                record(context, event(context, "locked").put("credentialRejected", rejected).put("deviceData", data));
            } else if (ALARM.equals(intent.getAction())) {
                PayloadParcel value = intent.getParcelableExtra("parcel");
                record(context, event(context, "alarm").put("parcel", value != null && ("alarm-λ-" + run).equals(value.value)));
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                initializeUnlocked(context, "bootCompleted");
                record(context, event(context, "bootCompleted"));
            }
        } catch (Exception error) { fail(context, error); }
    }
    public static final class PayloadParcel implements Parcelable {
        final String value;
        PayloadParcel(String value) { this.value = value; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel destination, int flags) { destination.writeString(value); }
        public static final Creator<PayloadParcel> CREATOR = new Creator<PayloadParcel>() {
            @Override public PayloadParcel createFromParcel(Parcel source) { return new PayloadParcel(source.readString()); }
            @Override public PayloadParcel[] newArray(int size) { return new PayloadParcel[size]; }
        };
    }
}
