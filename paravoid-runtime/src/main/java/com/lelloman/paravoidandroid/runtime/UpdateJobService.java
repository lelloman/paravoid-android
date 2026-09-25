package com.lelloman.paravoidandroid.runtime;

import android.app.job.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Framework-created shell worker. No payload classes, resources, or WorkerFactory. */
public final class UpdateJobService extends JobService {
    private static final AtomicLong TOKENS=new AtomicLong();
    private final Map<JobParameters,Long> running=new IdentityHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public boolean onStartJob(JobParameters parameters) {
        long token=TOKENS.incrementAndGet(); running.put(parameters,token);
        UpdateRuntime.engine(this).whenComplete((engine,failure)->main.post(()-> {
            if(!running.containsKey(parameters)) return;
            if(failure!=null) { running.remove(parameters); jobFinished(parameters,false); return; }
            String event=parameters.getExtras().getString("pushEvent");
            if(event!=null) {
                engine.pushEvent(event.getBytes(java.nio.charset.StandardCharsets.UTF_8),()-> {
                    if(running.remove(parameters)!=null) jobFinished(parameters,false);
                }); return;
            }
            engine.runJob(parameters.getExtras().getBoolean("download"),token,()-> {
                if(running.remove(parameters)!=null) jobFinished(parameters,false);
            });
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Long token=running.remove(parameters);
        if(token!=null) UpdateRuntime.engine(this).thenAccept(engine->engine.stopJob(token));
        return true; // System/constraint interruption, not a new automatic network retry budget.
    }
}
