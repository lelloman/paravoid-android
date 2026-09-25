package com.lelloman.paravoidandroid.runtime;

import android.app.job.*;
import android.content.*;
import android.os.*;
import com.lelloman.paravoidandroid.contract.Protocol.ShellPolicy;
import com.lelloman.paravoidandroid.delivery.UpdateEngine;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;

/** Reconciles only shell-owned jobs. Identical schedules do not replace a running job. */
final class UpdateScheduler {
    private final Context context;
    private final JobScheduler jobs;
    private final ComponentName component;
    private final int base;
    UpdateScheduler(Context context,ShellPolicy policy) {
        this.context=context; component=new ComponentName(context,UpdateJobService.class);
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        jobs=Build.VERSION.SDK_INT>=34 ? scheduler.forNamespace("com.lelloman.paravoid.updates") : scheduler;
        base=Integer.parseInt(policy.updates.getOrDefault("jobIdBase",""+0x50560000));
    }
    void reconcile(UpdateEngine.Work work) {
        UpdateSchedule s=work.schedule;
        if(s.checks) put(base,false,false,0,s,true);
        else cancel(base);
        long now=System.currentTimeMillis()/1000;
        if(work.kind==UpdateEngine.Kind.CHECK) put(base+1,false,work.explicit,work.dueSeconds,s,false);
        else if(work.kind==UpdateEngine.Kind.NONE && s.checks && work.nextCheckSeconds<=now) put(base+1,false,false,0,s,false);
        else cancel(base+1);
        if(work.kind==UpdateEngine.Kind.UPDATE && (work.explicit || s.checks && s.downloads)) put(base+2,true,work.explicit,work.dueSeconds,s,false);
        else cancel(base+2);
    }
    private void put(int id,boolean download,boolean explicit,long due,UpdateSchedule s,boolean periodic) {
        String signature=periodic ? "periodic:"+s.intervalSeconds+":"+s.flexSeconds : "once:"+download+":"+explicit+":"+due;
        boolean unmetered=!explicit && (download ? s.downloadUnmetered : s.checkUnmetered);
        signature+=":"+unmetered+":"+(!explicit && s.charging)+":"+(!explicit && s.batteryNotLow)+":"+(!explicit && s.deviceIdle);
        JobInfo existing=jobs.getPendingJob(id);
        if(existing!=null) {
            if(!component.equals(existing.getService())) throw new IllegalStateException("Update job ID already owned; configure jobIdBase");
            if(signature.equals(existing.getExtras().getString("signature"))) return;
        }
        PersistableBundle extras=new PersistableBundle(); extras.putString("signature",signature); extras.putBoolean("download",download);
        JobInfo.Builder builder=new JobInfo.Builder(id,component).setPersisted(true).setExtras(extras)
            .setRequiredNetworkType(unmetered ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(!explicit && s.charging).setRequiresBatteryNotLow(!explicit && s.batteryNotLow)
            .setRequiresDeviceIdle(!explicit && s.deviceIdle);
        if(download) builder.setRequiresStorageNotLow(true);
        if(periodic) builder.setPeriodic(s.intervalSeconds*1000,s.flexSeconds*1000);
        else builder.setMinimumLatency(Math.max(0,due-System.currentTimeMillis()/1000)*1000);
        if(jobs.schedule(builder.build())!=JobScheduler.RESULT_SUCCESS) android.util.Log.e("ParavoidAndroid","Unable to schedule update job");
    }
    private void cancel(int id) {
        JobInfo job=jobs.getPendingJob(id);
        if(job!=null && component.equals(job.getService())) jobs.cancel(id);
    }
}
