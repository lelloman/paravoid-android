package com.lelloman.paravoidandroid.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.lelloman.paravoidandroid.delivery.*;
import java.util.*;

/** Private command/observation IPC. The JobService shares the same process and engine. */
public final class UpdateService extends Service {
    private final Map<IBinder,DeliveryController.Listener> observers=new HashMap<>();
    private final Set<IBinder> visibleClients=new HashSet<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message -> {
        if(message.sendingUid!=android.os.Process.myUid()) return true;
        int command=message.what; Bundle data=new Bundle(message.getData()); Messenger reply=message.replyTo;
        UpdateRuntime.engine(this).whenComplete((engine,failure)->main.post(()-> {
            if(failure!=null) { failure(reply); return; }
            try {
                switch(command) {
                    case UpdateWire.WATCH:
                        if(reply!=null && !observers.containsKey(reply.getBinder())) {
                            DeliveryController.Listener listener=snapshot-> {
                                Message response=Message.obtain(null,UpdateWire.SNAPSHOT); response.setData(UpdateWire.snapshot(snapshot));
                                try { reply.send(response); } catch(RemoteException gone) {
                                    DeliveryController.Listener removed=observers.remove(reply.getBinder()); if(removed!=null) engine.unlisten(removed);
                                }
                            };
                            observers.put(reply.getBinder(),listener); engine.listen(listener);
                            try { reply.getBinder().linkToDeath(()->main.post(()-> {
                                DeliveryController.Listener removed=observers.remove(reply.getBinder());
                                if(removed!=null) engine.unlisten(removed);
                                visibleClients.remove(reply.getBinder()); UpdateRuntime.visible(this,!visibleClients.isEmpty());
                            }),0); } catch(RemoteException gone) { observers.remove(reply.getBinder()); engine.unlisten(listener); }
                        } else engine.refreshSnapshot();
                        break;
                    case UpdateWire.REFRESH: engine.refreshSnapshot(); engine.foreground(); break;
                    case UpdateWire.VISIBLE:
                        if(reply!=null) {
                            if(data.getBoolean("visible")) visibleClients.add(reply.getBinder()); else visibleClients.remove(reply.getBinder());
                            UpdateRuntime.visible(this,!visibleClients.isEmpty());
                        } break;
                    case UpdateWire.DISMISS: engine.dismiss(UpdateWire.offer(data.getBundle("offer"))); break;
                    case UpdateWire.CHECK: engine.checkNow(); break;
                    case UpdateWire.UPDATE:
                        if(data.getBundle("offer")==null) engine.updateNow(); else engine.updateNow(UpdateWire.offer(data.getBundle("offer"))); break;
                    case UpdateWire.RETRY: engine.retry(); break;
                    case UpdateWire.CANCEL: engine.cancelDownload(); break;
                    case UpdateWire.SCHEDULE:
                        if(data.getBoolean("preferencesOnly")) engine.preferences(new DeliveryPreferences(data.getBoolean("checks"),data.getBoolean("downloads"),data.getBoolean("unmetered")));
                        else engine.schedule(UpdateWire.schedule(data)); break;
                    case UpdateWire.RETAIN: engine.retainedPrevious(data.getInt("count")); break;
                    case UpdateWire.QUARANTINE: engine.retryQuarantinedAfterConfirmation(UpdateWire.offer(data.getBundle("offer"))); break;
                    default: break;
                }
            } catch(RuntimeException invalid) { failure(reply); }
        }));
        return true;
    }));
    private void failure(Messenger reply) {
        if(reply==null) return;
        DeliveryController.Snapshot failed=new DeliveryController.Snapshot(null,new DeliveryPreferences(false,false,true),DeliveryController.Activity.ERROR,null,"UPDATE_SERVICE_UNAVAILABLE");
        Message response=Message.obtain(null,UpdateWire.SNAPSHOT); response.setData(UpdateWire.snapshot(failed));
        try { reply.send(response); } catch(RemoteException ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }
    @Override public void onDestroy() {
        UpdateRuntime.visible(this,false);
        java.util.List<DeliveryController.Listener> removed=new java.util.ArrayList<>(observers.values());
        UpdateRuntime.engine(this).thenAccept(engine-> { for(DeliveryController.Listener listener:removed) engine.unlisten(listener); });
        observers.clear(); super.onDestroy();
    }
}
