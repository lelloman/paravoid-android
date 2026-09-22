package com.lelloman.paravoidcompat.complete;
import android.content.*;
public final class ProbeReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        if ("probe.die".equals(intent.getAction())) {
            // Installed-fixture-only abrupt process death. No lifecycle callback.
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        if ("probe.bind".equals(intent.getAction()) && context.getPackageName().equals("com.lelloman.paravoidcompat.complete")) {
            // The separately installed normal control is an actual external client.
            PendingResult pending = goAsync();
            Context app = context.getApplicationContext();
            ServiceConnection connection = new ServiceConnection() {
                private void done(String value) {
                    app.getSharedPreferences("probe", 0).edit().putString("binding", value).commit();
                    app.unbindService(this);
                    pending.finish();
                }
                public void onServiceConnected(ComponentName name, android.os.IBinder binder) { done("binder"); }
                public void onNullBinding(ComponentName name) { done("unavailable"); }
                public void onServiceDisconnected(ComponentName name) {}
                public void onBindingDied(ComponentName name) { done("died"); }
            };
            if (!app.bindService(new Intent().setComponent(new ComponentName(
                    "com.lelloman.paravoidcompat.complete.paravoid", ProbeService.class.getName())),
                    connection, Context.BIND_AUTO_CREATE)) {
                app.getSharedPreferences("probe", 0).edit().putString("binding", "rejected").commit();
                pending.finish();
            }
            return;
        }
        context.getSharedPreferences("probe", 0).edit().putString("receiver", context.getString(R.string.generation)).commit();
    }
}
