package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.delivery.DeliveryClient;
import com.lelloman.paravoidandroid.recovery.RecoveryUpdateProvider;
import com.lelloman.paravoidandroid.runtime.lifecycle.RuntimeLifecycle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** A process-scoped worker; Activity recreation never duplicates a transfer. */
final class RecoveryCoordinator {
    static final class State {
        final String message;
        final boolean busy, staging, ready;
        final ExpectedArchive offer;
        State(String message, boolean busy, boolean staging, boolean ready, ExpectedArchive offer) {
            this.message=message; this.busy=busy; this.staging=staging; this.ready=ready; this.offer=offer;
        }
    }
    private final DeliveryClient client;
    private final RuntimeLifecycle lifecycle;
    private final RequestScope scope;
    private final ShellPolicy policy;
    private final AndroidLifecycleEnvironment environment;
    private final CrashRecords records;
    private final String providerClass;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Consumer<State> observer;
    private volatile State state=new State("Ready to check for updates.",false,false,false,null);
    private boolean started, initialized;
    private volatile boolean cancelled;
    private long lastProgress;
    RecoveryCoordinator(Application app, DeliveryClient client, RuntimeLifecycle lifecycle, RequestScope scope,
            ShellPolicy policy, AndroidLifecycleEnvironment environment, CrashRecords records, String providerClass) {
        this.client=client; this.lifecycle=lifecycle; this.scope=scope; this.policy=policy;
        this.environment=environment; this.records=records; this.providerClass=providerClass;
        client.progress((phase,bytes,total) -> {
            long now=android.os.SystemClock.elapsedRealtime();
            if (phase.equals("STAGING") || now-lastProgress>=200) {
                lastProgress=now;
                publish(new State(phase.equals("STAGING") ? "Verifying and staging update…" :
                    "Downloading update: "+bytes+" / "+total+" bytes",true,phase.equals("STAGING"),false,null));
            }
        });
    }
    void observe(Consumer<State> listener) { observer=listener; listener.accept(state); }
    void detach(Consumer<State> listener, boolean cancelWork) {
        if(observer==listener) { observer=null; if(cancelWork) cancel(); }
    }
    void start() {
        if (started) return;
        started=true;
        if (records.providerInterrupted()) {
            publish(new State("The previous update provider was interrupted. Choose Check again to retry.",false,false,false,null));
        } else check(false);
    }
    void check(boolean explicit) { run(null,explicit); }
    void update() { ExpectedArchive offer=state.offer; if (offer!=null) run(offer,true); }
    void cancel() { if (!state.staging) { cancelled=true; client.cancelDownload(); } }
    private void run(ExpectedArchive offered, boolean explicit) {
        if (state.busy) return;
        if (!policy.updatesEnabled) { publish(new State("Updates are disabled in this shell.",false,false,false,null)); return; }
        cancelled=false;
        publish(new State("Checking for updates…",true,false,false,null));
        worker.execute(() -> {
            boolean guarded=false, completed=false;
            try {
                if (!providerClass.isEmpty()) { records.providerStarted(); guarded=true; }
                if (!initialized) {
                    client.installedApk(environment.currentBaseApk());
                    if (!providerClass.isEmpty()) client.recoveryProvider((RecoveryUpdateProvider)
                        Class.forName(providerClass,true,RecoveryCoordinator.class.getClassLoader()).getConstructor().newInstance());
                    initialized=true;
                }
                if (cancelled) throw new java.io.InterruptedIOException();
                DeliveryClient.Result result=client.recoveryCheck(scope,offered,explicit,() -> cancelled);
                LifecycleSnapshot snapshot=lifecycle.snapshot();
                boolean ready=snapshot.pending!=null;
                String message=ready ? "Update ready. Restart to apply it." :
                    result.status==HeadStatus.SHELL_UPDATE_REQUIRED ? "A new app installation is required to update the shell." :
                    result.available!=null ? "Update available: "+result.available.payloadVersion : "No compatible update is available.";
                publish(new State(message,false,false,ready,ready ? null : result.available));
                completed=true;
            } catch (Exception | LinkageError error) {
                String message=cancelled ? "Update cancelled." : error instanceof ContractException ?
                    "Update unavailable ("+((ContractException)error).code+")." : "Could not check or download the update. Try again.";
                boolean ready=false;
                try { ready=lifecycle.snapshot().pending!=null; } catch (ContractException ignored) { }
                if (ready) message += " An update is already staged. Restart to apply it.";
                publish(new State(message,false,false,ready,null));
                completed=true;
            } finally {
                if (guarded && completed) try { records.providerFinished(); } catch (java.io.IOException ignored) { }
            }
        });
    }
    private void publish(State value) {
        state=value;
        main.post(() -> { if (observer!=null) observer.accept(state); });
    }
}
