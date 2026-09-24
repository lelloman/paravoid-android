package com.lelloman.paravoidandroid.runtime;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Only called after explicit destructive-restart confirmation in shell controls. */
final class ShellRestart {
    interface BeforeLaunch { void run() throws Exception; }
    static void restart(Application app, Consumer<Boolean> result) { restart(app, () -> {}, result); }
    static void restart(Application app, BeforeLaunch beforeLaunch, Consumer<Boolean> result) {
        Handler main = new Handler(Looper.getMainLooper());
        RestartProcessGate gate = new RestartProcessGate(new RestartProcessGate.Platform() {
            public boolean exclusiveUid() {
                String[] packages = app.getPackageManager().getPackagesForUid(Process.myUid());
                return packages != null && packages.length == 1 && app.getPackageName().equals(packages[0]);
            }
            public List<RestartProcessGate.Entry> processes() {
                ActivityManager manager = app.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                if (processes == null) return null;
                List<RestartProcessGate.Entry> entries = new ArrayList<>();
                for (ActivityManager.RunningAppProcessInfo process : processes)
                    entries.add(new RestartProcessGate.Entry(process.pid, process.uid, process.pkgList));
                return entries;
            }
            public void kill(int pid) { Process.killProcess(pid); }
            public long elapsed() { return SystemClock.elapsedRealtime(); }
            public void sleep() { SystemClock.sleep(100); }
        }, Process.myUid(), Process.myPid(), app.getPackageName());
        new Thread(() -> {
            boolean prepared = gate.stop();
            if (prepared) try { beforeLaunch.run(); }
            catch (Exception unavailable) { prepared=false; }
            final boolean ready = prepared;
            main.post(() -> {
                boolean launched = false;
                if (ready && gate.readyToLaunch()) try {
                    app.startActivity(new Intent(app, LauncherActivity.class)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    launched = true;
                } catch (Exception unavailable) { /* Keep recovery screen available. */ }
                result.accept(launched);
            });
        }, "paravoid-explicit-restart").start();
    }
}
