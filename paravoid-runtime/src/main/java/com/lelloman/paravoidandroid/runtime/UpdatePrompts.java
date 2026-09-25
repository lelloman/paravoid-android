package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.*;
import com.lelloman.paravoidandroid.delivery.*;

/** User approval is bound to the verified offer, never to data from a push event. */
final class UpdatePrompts implements DeliveryController.Listener {
    private static final String CHANNEL="paravoid_updates";
    private static final int ID=0x50565550;
    private final Context context;
    UpdatePrompts(Context context,UpdateEngine engine) { this.context=context; }
    public void changed(DeliveryController.Snapshot snapshot) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(!snapshot.promptRequired) { manager.cancel(ID); return; }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"App updates",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(context,UpdatePromptActivity.class).putExtra("offer",UpdateWire.offer(snapshot.available));
        PendingIntent action=PendingIntent.getActivity(context,ID,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available").setContentText("Install version "+snapshot.available.payloadVersion+"?")
            .setContentIntent(action).setAutoCancel(true).build();
        try { manager.notify(ID,notification); } catch(SecurityException permissionUnavailable) { /* Offer remains visible through update controls. */ }
    }
}
