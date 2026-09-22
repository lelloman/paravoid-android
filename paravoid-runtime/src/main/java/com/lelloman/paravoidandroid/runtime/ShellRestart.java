package com.lelloman.paravoidandroid.runtime;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.util.List;
import java.util.function.Consumer;

/** Only called after explicit destructive-restart confirmation in shell controls. */
final class ShellRestart {
    static void restart(Application app, Consumer<Boolean> result) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean stopped = false;
            try {
                ActivityManager manager = app.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                if (processes == null) throw new IllegalStateException("Process list unavailable");
                // Do not terminate another package in legacy shared-UID configurations.
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.uid != Process.myUid() || process.pid == Process.myPid()) continue;
                    if (!owned(app, process)) throw new IllegalStateException("Shared UID cannot restart safely");
                }
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.uid == Process.myUid() && process.pid != Process.myPid()) Process.killProcess(process.pid);
                }
                // OS process death releases leases; never fake lease release or reset the journal.
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    processes = manager.getRunningAppProcesses();
                    if (processes != null && processes.stream().noneMatch(p -> p.uid == Process.myUid() && p.pid != Process.myPid())) {
                        stopped = true; break;
                    }
                    SystemClock.sleep(100);
                }
            } catch (RuntimeException unavailable) { /* Fixed UI error, no platform/payload text. */ }
            final boolean ready = stopped;
            main.post(() -> {
                boolean launched = false;
                if (ready) try {
                    app.startActivity(new Intent(app, LauncherActivity.class)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    launched = true;
                } catch (RuntimeException unavailable) { /* Keep recovery screen available. */ }
                result.accept(launched);
            });
        }, "paravoid-explicit-restart").start();
    }
    private static boolean owned(Application app, ActivityManager.RunningAppProcessInfo process) {
        return process.pkgList != null && process.pkgList.length == 1 && app.getPackageName().equals(process.pkgList[0]);
    }
}
