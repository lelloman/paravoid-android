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

/** Invoked by shell controls, downstream commands, or the installed restart policy. */
final class ShellRestart {
    private static final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    static boolean inProgress() { return running.get(); }
    interface BeforeLaunch { void run() throws Exception; }
    static void restart(Application app, Consumer<Boolean> result) { restart(app, () -> {}, result); }
    static void restart(Application app, BeforeLaunch beforeLaunch, Consumer<Boolean> result) {
        Handler main = new Handler(Looper.getMainLooper());
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post(()->restart(app,beforeLaunch,result)); return; }
        if(!running.compareAndSet(false,true)) { result.accept(false); return; }
        ShellApplication.requireInstance().suspendUpdates();
        RestartProcessGate gate = new RestartProcessGate(new RestartProcessGate.Platform() {
            public boolean exclusiveUid() {
                String[] packages = app.getPackageManager().getPackagesForUid(Process.myUid());
                return packages != null && packages.length == 1 && app.getPackageName().equals(packages[0]);
            }
            public List<RestartProcessGate.Entry> processes() {
                ActivityManager manager = app.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                if (processes == null) return null;
                // Stop clients before their update service, avoiding binding-driven respawn.
                processes=new ArrayList<>(processes);
                processes.sort(java.util.Comparator.comparingInt(process ->
                    process.processName.equals(app.getPackageName()+UpdateRuntime.SUFFIX) ? 2 :
                    process.processName.equals(app.getPackageName()) ? 0 : 1));
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
                running.set(false);
                if(!launched) ShellApplication.requireInstance().resumeUpdates();
                result.accept(launched);
            });
        }, "paravoid-explicit-restart").start();
    }
}
