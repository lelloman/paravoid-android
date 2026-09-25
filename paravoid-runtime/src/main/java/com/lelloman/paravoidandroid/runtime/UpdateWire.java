package com.lelloman.paravoidandroid.runtime;

import android.os.Bundle;
import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
import java.util.*;

/** IPC contains framework primitives only; no payload class loader or credentials. */
final class UpdateWire {
    static final int WATCH=1, SNAPSHOT=2, CHECK=3, UPDATE=4, RETRY=5, CANCEL=6, SCHEDULE=7, RETAIN=8, QUARANTINE=9, REFRESH=10, VISIBLE=11, DISMISS=12;
    static Bundle offer(ExpectedArchive a) {
        if(a==null) return null;
        Bundle b=new Bundle(); b.putString("id",a.releaseId); b.putLong("version",a.payloadVersion); b.putLong("size",a.archiveSize);
        b.putString("manifest",a.manifestSha256); b.putString("archive",a.archiveSha256); return b;
    }
    static ExpectedArchive offer(Bundle b) {
        return b==null ? null : new ExpectedArchive(b.getString("id"),b.getLong("version"),b.getString("manifest"),b.getString("archive"),b.getLong("size"));
    }
    static Bundle schedule(UpdateSchedule s) {
        Bundle b=new Bundle(); UpdateScheduleCodec.write(s).forEach(b::putString); return b;
    }
    static UpdateSchedule schedule(Bundle b) {
        Map<String,String> p=new LinkedHashMap<>(); for(String key:b.keySet()) p.put(key,b.getString(key)); return UpdateScheduleCodec.read(p);
    }
    static Bundle snapshot(DeliveryController.Snapshot s) {
        Bundle b=new Bundle(); b.putString("phase",s.activity.name()); b.putString("error",s.errorCode); b.putBundle("available",offer(s.available));
        b.putBundle("schedule",schedule(s.schedule)); b.putLong("bytes",s.bytes); b.putLong("total",s.totalBytes);
        b.putBoolean("promptRequired",s.promptRequired); b.putLong("lastCheck",s.lastCheckSeconds); b.putLong("nextDue",s.nextDueSeconds);
        LifecycleSnapshot l=s.lifecycle;
        if(l!=null) {
            b.putString("availability",l.availability.name()); b.putBundle("active",offer(l.active)); b.putBundle("pending",offer(l.pending)); b.putBundle("healthy",offer(l.lastHealthy));
            b.putBoolean("waiting",l.waitingForProcesses); b.putInt("retained",l.retainedPrevious); b.putLong("storage",l.storageBytes);
            b.putString("lifecycleError",l.error==null ? null : l.error.name());
        }
        return b;
    }
    static DeliveryController.Snapshot snapshot(Bundle b) {
        UpdateSchedule s=schedule(b.getBundle("schedule"));
        String error=b.getString("lifecycleError");
        LifecycleSnapshot life=b.containsKey("availability") ? new LifecycleSnapshot(Availability.valueOf(b.getString("availability")),
            offer(b.getBundle("active")),offer(b.getBundle("pending")),offer(b.getBundle("healthy")),b.getBoolean("waiting"),b.getInt("retained"),b.getLong("storage"),
            error==null ? null : ContractException.Code.valueOf(error)) : null;
        return new DeliveryController.Snapshot(life,new DeliveryPreferences(s.checks,s.downloads,s.downloadUnmetered),
            DeliveryController.Activity.valueOf(b.getString("phase")),offer(b.getBundle("available")),b.getString("error"),s,
            b.getLong("bytes"),b.getLong("total"),b.getLong("lastCheck"),b.getLong("nextDue"),b.getBoolean("promptRequired"));
    }
}
