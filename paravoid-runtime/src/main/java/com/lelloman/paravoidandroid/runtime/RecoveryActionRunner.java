package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.lelloman.paravoidandroid.recovery.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the optional {@link RecoveryActionProvider} in the recovery process. Process-scoped so a
 * running action survives screen recreation; leaving the screen cancels it. Main thread only.
 */
final class RecoveryActionRunner {
    static final String METADATA="paravoid.recoveryActions";
    interface Observer { void changed(String running,Outcome outcome); }
    /** A finished action: its result, or failed when it threw. */
    static final class Outcome {
        final RecoveryActionResult result; final boolean failed;
        Outcome(RecoveryActionResult result,boolean failed) { this.result=result; this.failed=failed; }
    }
    private static RecoveryActionRunner instance;
    private final RecoveryActionProvider provider;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Observer observer;
    private String running;
    private Signal signal;
    private Outcome undelivered;

    RecoveryActionRunner(RecoveryActionProvider provider) { this.provider=provider; }

    /** The configured provider's runner, or null when the shell has none or it cannot load. */
    static RecoveryActionRunner get(Context context) {
        if(instance==null) {
            RecoveryActionProvider provider=null;
            try {
                android.os.Bundle metadata=context.getPackageManager().getApplicationInfo(context.getPackageName(),
                    android.content.pm.PackageManager.GET_META_DATA).metaData;
                String name=metadata==null ? "" : metadata.getString(METADATA,"");
                if(!name.isEmpty()) provider=(RecoveryActionProvider)Class.forName(name,true,RecoveryActionRunner.class.getClassLoader())
                    .getConstructor().newInstance();
            } catch(Throwable unavailable) {
                android.util.Log.e("ParavoidAndroid","Recovery actions unavailable",unavailable);
            }
            instance=new RecoveryActionRunner(provider);
        }
        return instance.provider==null ? null : instance;
    }

    /** The provider's buttons; a failing provider shows none rather than breaking recovery. */
    List<RecoveryAction> actions(RecoveryReport report) {
        try {
            List<RecoveryAction> actions=provider.actions(report);
            if(actions==null) return Collections.emptyList();
            List<RecoveryAction> valid=new ArrayList<>();
            for(RecoveryAction action:actions) if(action!=null) valid.add(action);
            return valid;
        } catch(Throwable failure) {
            android.util.Log.e("ParavoidAndroid","Recovery actions failed",failure);
            return Collections.emptyList();
        }
    }

    boolean running() { return running!=null; }

    void perform(RecoveryAction action,RecoveryReport report) {
        if(running!=null) return;
        running=action.id; Signal current=signal=new Signal(); undelivered=null;
        notifyObserver(null);
        worker.execute(() -> {
            Outcome outcome;
            try {
                RecoveryActionResult result=provider.perform(action.id,report,current);
                outcome=new Outcome(result==null ? RecoveryActionResult.none() : result,false);
            } catch(Throwable failure) {
                if(!current.cancelled()) android.util.Log.e("ParavoidAndroid","Recovery action failed",failure);
                outcome=new Outcome(null,true);
            }
            Outcome finished=outcome;
            main.post(() -> {
                if(signal!=current) return;
                running=null; signal=null;
                // The user left the screen: nothing to show for a cancelled action.
                if(current.cancelled()) { notifyObserver(null); return; }
                if(observer==null) undelivered=finished; else notifyObserver(finished);
            });
        });
    }

    void observe(Observer listener) {
        observer=listener;
        Outcome pending=undelivered; undelivered=null;
        listener.changed(running,pending);
    }

    void detach(Observer listener,boolean cancel) {
        if(observer!=listener) return;
        observer=null;
        if(cancel && signal!=null) signal.cancel();
    }

    private void notifyObserver(Outcome outcome) { if(observer!=null) observer.changed(running,outcome); }

    static final class Signal implements Cancellation {
        private final Set<Runnable> disconnects=new HashSet<>();
        private boolean cancelled;
        synchronized boolean cancelled() { return cancelled; }
        void cancel() {
            List<Runnable> actions;
            synchronized(this) { if(cancelled) return; cancelled=true; actions=new ArrayList<>(disconnects); }
            for(Runnable action:actions) try { action.run(); } catch(RuntimeException ignored) { }
        }
        @Override public void check() throws IOException { if(cancelled()) throw new IOException("cancelled"); }
        @Override public AutoCloseable onCancel(Runnable disconnect) {
            boolean now;
            synchronized(this) { now=cancelled; if(!now) disconnects.add(disconnect); }
            if(now) disconnect.run();
            return () -> { synchronized(this) { disconnects.remove(disconnect); } };
        }
    }
}
