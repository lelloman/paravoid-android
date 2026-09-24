package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.Intent;
import android.os.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local bridge. Durable records coordinate processes without payload code. */
final class CrashRecovery {
    static volatile CrashRecovery instance;
    final Application app;
    final CrashRecords records;
    final boolean recoveryProcess;
    final String providerClass;
    final String shellDescription;
    volatile RecoveryCoordinator coordinator;
    volatile CompleteRuntime runtime;
    private volatile String identity="unknown";
    private volatile int visible;
    private final AtomicBoolean recorded=new AtomicBoolean();
    CrashRecovery(Application app, String providerClass) {
        this.app=app; this.providerClass=providerClass;
        String version="unavailable";
        try { android.content.pm.PackageInfo info=app.getPackageManager().getPackageInfo(app.getPackageName(),0);
            version=info.versionName+" ("+info.getLongVersionCode()+")";
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        shellDescription="App: "+app.getPackageName()+"\nShell: "+version;
        records=new CrashRecords(new File(app.getNoBackupFilesDir(),"paravoid-crash-recovery"));
        recoveryProcess=Application.getProcessName().equals(app.getPackageName()+CompleteRuntime.RECOVERY_SUFFIX);
        instance=this;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { visible++; }
            public void onActivityStopped(Activity a) { visible=Math.max(0,visible-1); }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a,Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
        install();
    }
    void identity(String contract, ExpectedArchive release) {
        identity=release==null ? "unknown" : contract+":"+release.releaseId+":"+release.payloadVersion+":"+release.manifestSha256;
    }
    boolean blocked() throws IOException { return records.pending(identity); }
    void install() {
        if (recoveryProcess) return; // A broken provider must not recursively launch recovery.
        Thread main=Looper.getMainLooper().getThread();
        Thread.UncaughtExceptionHandler originalMain=main.getUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler originalDefault=Thread.getDefaultUncaughtExceptionHandler();
        Runnable terminate=() -> { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); };
        if (!(originalDefault instanceof CrashExceptionHandler))
            Thread.setDefaultUncaughtExceptionHandler(new CrashExceptionHandler(originalDefault,this::capture,terminate));
        // A ThreadGroup delegates to the new default; do not wrap it or create a recursive chain.
        if (!(originalMain instanceof ThreadGroup) && !(originalMain instanceof CrashExceptionHandler))
            main.setUncaughtExceptionHandler(new CrashExceptionHandler(originalMain,this::capture,terminate));
    }
    void capture(Thread thread, Throwable error) {
        if (recoveryProcess || !recorded.compareAndSet(false,true)) return;
        try { records.record(identity, shellDescription, Application.getProcessName(), thread.getName(),error); }
        catch (Throwable ignored) { /* Low storage/VM failure cannot prevent Android crash handling. */ }
        if (visible>0) try {
            app.startActivity(new Intent(app,CrashRecoveryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { /* The durable marker gates the next user launch. */ }
    }
    void restart(java.util.function.Consumer<Boolean> result) {
        ShellRestart.restart(app, () -> {
            if (runtime!=null) {
                LifecycleSnapshot state=runtime.lifecycle.snapshot();
                if (state.availability==Availability.RECOVERY && state.pending==null && state.active!=null)
                    runtime.lifecycle.retryQuarantined(state.active);
            }
            records.acknowledge();
        }, result);
    }
    static final class Required extends IOException { Required() { super("Crash recovery is pending"); } }
}
