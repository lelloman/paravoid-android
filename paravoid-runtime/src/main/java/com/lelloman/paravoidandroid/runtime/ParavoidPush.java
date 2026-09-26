package com.lelloman.paravoidandroid.runtime;

import android.app.job.*;
import com.lelloman.paravoidandroid.delivery.ApkInstalledStateSource;
import android.content.*;
import android.os.*;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.charset.StandardCharsets;

/** Entry point for any downstream notification SDK. Payload startup is not required. */
public final class ParavoidPush {
    private ParavoidPush() {}
    /** Whether this shell includes the background WebSocket service. */
    public static boolean backgroundAvailable(Context context) { return PushForegroundService.available(context); }
    /** Persisted user choice; false on a new installation. */
    public static boolean backgroundEnabled(Context context) { return BackgroundPushPreference.read(context); }
    /** Toggle from a visible screen; true means the setting was saved and the start/stop requested. */
    public static boolean backgroundEnabled(android.app.Activity activity,boolean enabled) {
        return enabled ? startBackground(activity) : PushForegroundService.pause(activity);
    }
    /** Resume a configured background connection from a visible Activity. True means start requested. */
    public static boolean startBackground(android.app.Activity activity) {
        return PushForegroundService.start(activity,true);
    }
    /** Persistently pause background connection; ordinary visible-app push and polling remain available. */
    public static void stopBackground(Context context) { PushForegroundService.pause(context); }
    public static boolean receive(Context context, byte[] event) {
        if(event==null || event.length>UpdateEventCodec.MAX_BYTES) return false;
        try {
            AndroidLifecycleEnvironment env=new AndroidLifecycleEnvironment(context);
            ShellPolicy policy=new ApkInstalledStateSource(env,new SignedMetadataVerifier()).read().policy;
            if(!policy.updatesEnabled || !Boolean.parseBoolean(policy.updates.get("pushEnabled"))) return false;
            UpdateEventCodec.verifyScope(event,env.scope(policy));
            JobScheduler jobs=context.getSystemService(JobScheduler.class);
            if(Build.VERSION.SDK_INT>=34) jobs=jobs.forNamespace("com.lelloman.paravoid.updates");
            int id=Integer.parseInt(policy.updates.getOrDefault("jobIdBase",""+0x50560000))+3;
            ComponentName component=new ComponentName(context,UpdateJobService.class);
            JobInfo existing=jobs.getPendingJob(id);
            if(existing!=null && !component.equals(existing.getService())) return false;
            PersistableBundle extras=new PersistableBundle(); extras.putString("pushEvent",new String(event,StandardCharsets.UTF_8));
            return jobs.schedule(new JobInfo.Builder(id,component).setPersisted(true).setExtras(extras)
                .setMinimumLatency(0).build())==JobScheduler.RESULT_SUCCESS;
        } catch(Exception invalid) { return false; }
    }
}
