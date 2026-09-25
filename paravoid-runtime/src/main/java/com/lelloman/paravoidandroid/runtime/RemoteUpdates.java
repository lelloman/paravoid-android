package com.lelloman.paravoidandroid.runtime;

import android.content.*;
import android.os.*;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;
import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
import java.util.*;

/** A process-local facade for the shell's single update owner. */
final class RemoteUpdates implements UpdateControl {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<DeliveryController.Listener> listeners=new LinkedHashSet<>();
    private final ArrayDeque<Message> pending=new ArrayDeque<>();
    private DeliveryController.Snapshot snapshot;
    private Messenger service;
    private boolean bound, visible, suspended;
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message -> {
        if(message.what==UpdateWire.SNAPSHOT) {
            snapshot=UpdateWire.snapshot(message.getData());
            for(DeliveryController.Listener listener:new ArrayList<>(listeners)) listener.changed(snapshot);
        }
        return true;
    }));
    RemoteUpdates(Context context) { this.context=context.getApplicationContext(); }
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) {
            if(suspended) return;
            service=new Messenger(binder); send(UpdateWire.WATCH,new Bundle()); visible(visible);
            while(!pending.isEmpty() && service!=null) deliver(pending.removeFirst());
        }
        public void onServiceDisconnected(ComponentName name) { service=null; }
        public void onBindingDied(ComponentName name) {
            service=null; if(bound) context.unbindService(this); bound=false; connect();
        }
        public void onNullBinding(ComponentName name) { service=null; if(bound) context.unbindService(this); bound=false; }
    };
    private void connect() {
        if(!suspended && !bound) bound=context.bindService(new Intent(context,UpdateService.class),connection,Context.BIND_AUTO_CREATE);
    }
    private void send(int command,Bundle data) {
        main.post(()-> {
            if(suspended) return;
            Message message=Message.obtain(null,command); message.setData(data); message.replyTo=inbox;
            if(service==null) { if(pending.size()<64) pending.add(message); connect(); }
            else deliver(message);
        });
    }
    private void deliver(Message message) {
        try { service.send(message); } catch(RemoteException gone) { service=null; if(pending.size()<64) pending.addFirst(message); }
    }
    public void listen(DeliveryController.Listener listener) {
        main.post(()-> { listeners.add(listener); if(snapshot!=null) listener.changed(snapshot); send(UpdateWire.WATCH,new Bundle()); });
    }
    public void unlisten(DeliveryController.Listener listener) { main.post(()->listeners.remove(listener)); }
    /** Called on the main thread before the recovery coordinator stops other processes. */
    void suspend() {
        suspended=true; pending.clear(); service=null;
        if(bound) { context.unbindService(connection); bound=false; }
    }
    void resume() {
        if(!suspended) return;
        suspended=false; send(UpdateWire.WATCH,new Bundle());
    }
    public void refreshSnapshot() { send(UpdateWire.REFRESH,new Bundle()); }
    public void foreground() { refreshSnapshot(); }
    public void checkNow() { send(UpdateWire.CHECK,new Bundle()); }
    public void updateNow() { send(UpdateWire.UPDATE,new Bundle()); }
    public void updateNow(ExpectedArchive offer) { Bundle b=new Bundle(); b.putBundle("offer",UpdateWire.offer(offer)); send(UpdateWire.UPDATE,b); }
    public void dismiss(ExpectedArchive offer) { Bundle b=new Bundle(); b.putBundle("offer",UpdateWire.offer(offer)); send(UpdateWire.DISMISS,b); }
    void visible(boolean visible) { this.visible=visible; Bundle b=new Bundle(); b.putBoolean("visible",visible); send(UpdateWire.VISIBLE,b); }
    public void retry() { send(UpdateWire.RETRY,new Bundle()); }
    public void cancelDownload() { send(UpdateWire.CANCEL,new Bundle()); }
    public void preferences(DeliveryPreferences value) {
        Bundle b=new Bundle(); b.putBoolean("checks",value.automaticChecks); b.putBoolean("downloads",value.automaticDownloads); b.putBoolean("unmetered",value.unmeteredOnly);
        b.putBoolean("preferencesOnly",true); send(UpdateWire.SCHEDULE,b);
    }
    public void schedule(UpdateSchedule value) { send(UpdateWire.SCHEDULE,UpdateWire.schedule(value)); }
    public void retainedPrevious(int count) { Bundle b=new Bundle(); b.putInt("count",count); send(UpdateWire.RETAIN,b); }
    public void retryQuarantinedAfterConfirmation(ExpectedArchive offer) { Bundle b=new Bundle(); b.putBundle("offer",UpdateWire.offer(offer)); send(UpdateWire.QUARANTINE,b); }
}
