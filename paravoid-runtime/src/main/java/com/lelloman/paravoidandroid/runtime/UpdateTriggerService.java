package com.lelloman.paravoidandroid.runtime;

import android.app.Service;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.ShellPolicy;
import com.lelloman.paravoidandroid.delivery.ApkInstalledStateSource;
import com.lelloman.paravoidandroid.updates.ipc.*;
import java.util.*;
import static com.lelloman.paravoidandroid.updates.ipc.UpdateTriggerProtocol.*;

/** Public hint-only endpoint. Authentication precedes engine/provider initialization. */
public final class UpdateTriggerService extends Service {
    private ShellPolicy policy;
    private Map<String,List<String>> callers=Collections.emptyMap();
    @Override public void onCreate() {
        super.onCreate();
        try {
            policy=new ApkInstalledStateSource(new AndroidLifecycleEnvironment(this),new SignedMetadataVerifier()).read().policy;
            if(policy.updatesEnabled) callers=LocalTriggerCallers.read(policy.updates.get("localTriggerCallers"));
        } catch(Exception unavailable) { Log.w("ParavoidTrigger","Installed trigger policy unavailable"); }
    }
    private final IUpdateTriggerV1.Stub binder=new IUpdateTriggerV1.Stub() {
        @Override public void notifyUpdatesChanged(IUpdateTriggerCallbackV1 callback) {
            // Never move this identity lookup to a Handler or executor.
            final int uid=Binder.getCallingUid();
            int result=REJECTED;
            try {
                PackageManager pm=getPackageManager();
                boolean trusted=TriggerCallerVerifier.trusted(uid,callers,new TriggerCallerVerifier.Identity() {
                    public String[] packages(int caller) { return pm.getPackagesForUid(caller); }
                    @SuppressWarnings("deprecation")
                    public boolean signedBy(String name,String fingerprint) {
                        // Package visibility may hide a sibling from getPackagesForUid.
                        // Reject declared shared identities even when only one package is visible.
                        try { if(pm.getPackageInfo(name,0).sharedUserId!=null) return false; }
                        catch(PackageManager.NameNotFoundException gone) { return false; }
                        byte[] digest=new byte[32];
                        for(int i=0;i<digest.length;i++) digest[i]=(byte)Integer.parseInt(fingerprint.substring(i*2,i*2+2),16);
                        return pm.hasSigningCertificate(name,digest,PackageManager.CERT_INPUT_SHA256);
                    }
                });
                if(trusted) {
                    long identity=Binder.clearCallingIdentity();
                    try { result=enqueue(); } finally { Binder.restoreCallingIdentity(identity); }
                }
            } catch(RuntimeException unavailable) { result=UNAVAILABLE; }
            Log.d("ParavoidTrigger","Hint result="+result);
            if(callback!=null) try { callback.onResult(result); } catch(RemoteException gone) { /* Durable work survives caller death. */ }
        }
    };
    private int enqueue() {
        synchronized(UpdateJobService.LOCAL_HINT_LOCK) {
            if(policy==null || !policy.updatesEnabled || callers.isEmpty()) return UNAVAILABLE;
            JobScheduler jobs=getSystemService(JobScheduler.class);
            if(Build.VERSION.SDK_INT>=34) jobs=jobs.forNamespace("com.lelloman.paravoid.updates");
            int id=Integer.parseInt(policy.updates.getOrDefault("jobIdBase",""+0x50560000))+4;
            ComponentName component=new ComponentName(this,UpdateJobService.class);
            JobInfo existing=jobs.getPendingJob(id);
            if(existing!=null && !component.equals(existing.getService())) return UNAVAILABLE;
            // Running ingress jobs must not swallow a newer hint. Replacing one causes
            // onStopJob; its durable replacement will process the latest hint as well.
            if(existing!=null && !UpdateJobService.localHintStarted()) return COALESCED;
            PersistableBundle extras=new PersistableBundle(); extras.putBoolean("localTrigger",true);
            int result=jobs.schedule(new JobInfo.Builder(id,component).setPersisted(true).setExtras(extras)
                .setMinimumLatency(0).build());
            if(result!=JobScheduler.RESULT_SUCCESS) return UNAVAILABLE;
            UpdateJobService.localHintQueued();
            return QUEUED;
        }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
}
