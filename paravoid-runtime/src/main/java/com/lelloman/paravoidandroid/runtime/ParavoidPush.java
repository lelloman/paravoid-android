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
