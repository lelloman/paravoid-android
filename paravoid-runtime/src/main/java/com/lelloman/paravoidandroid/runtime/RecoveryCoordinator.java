package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.*;
import java.util.function.Consumer;

/** Recovery UI shares the updater process; it cannot create an independent transfer. */
final class RecoveryCoordinator {
    static final class State {
        final String message;
        final boolean busy, staging, ready;
        final ExpectedArchive offer;
        State(String message,boolean busy,boolean staging,boolean ready,ExpectedArchive offer) {
            this.message=message; this.busy=busy; this.staging=staging; this.ready=ready; this.offer=offer;
        }
    }
    private final UpdateControl controls;
    private final CrashRecords records;
    private Consumer<State> observer;
    private State state=new State("Ready to check for updates.",false,false,false,null);
    private boolean started;
    RecoveryCoordinator(UpdateControl controls,CrashRecords records) {
        this.controls=controls; this.records=records;
        controls.listen(snapshot -> {
            boolean staging=snapshot.activity==DeliveryController.Activity.STAGING;
            boolean busy=staging || snapshot.activity==DeliveryController.Activity.CHECKING || snapshot.activity==DeliveryController.Activity.DOWNLOADING;
            boolean ready=snapshot.lifecycle!=null && snapshot.lifecycle.pending!=null;
            String message=ready ? "Update ready. Restart to apply it." : staging ? "Verifying and staging update…" :
                snapshot.activity==DeliveryController.Activity.DOWNLOADING ? "Downloading update: "+snapshot.bytes+" / "+snapshot.totalBytes+" bytes" :
                snapshot.errorCode!=null ? "Update unavailable ("+snapshot.errorCode+")." : snapshot.available!=null ? "Update available: "+snapshot.available.payloadVersion :
                busy ? "Checking for updates…" : "No compatible update is available.";
            state=new State(message,busy,staging,ready,ready ? null : snapshot.available);
            if(observer!=null) observer.accept(state);
        });
    }
    void observe(Consumer<State> listener) { observer=listener; listener.accept(state); }
    void detach(Consumer<State> listener,boolean cancel) { if(observer==listener) { observer=null; if(cancel) cancel(); } }
    void start() { if(!started) { started=true; if(!records.providerInterrupted()) controls.foreground(); } }
    void check(boolean explicit) { controls.checkNow(); }
    void update() { if(state.offer!=null) controls.updateNow(state.offer); }
    void cancel() { if(!state.staging) controls.cancelDownload(); }
}
