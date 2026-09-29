package com.lelloman.paravoidandroid.runtime;

import android.app.job.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Framework-created shell worker. No payload classes, resources, or WorkerFactory. */
public final class UpdateJobService extends JobService {
    private static final AtomicLong TOKENS=new AtomicLong();
    static final Object LOCAL_HINT_LOCK=new Object();
    private static volatile boolean localStarted;
    static boolean localHintStarted() { return localStarted; }
    static void localHintQueued() { localStarted=false; }
    private final Map<JobParameters,Long> running=new IdentityHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public boolean onStartJob(JobParameters parameters) {
        if(parameters.getExtras().getBoolean("localTrigger")) synchronized(LOCAL_HINT_LOCK) { localStarted=true; }
        long token=TOKENS.incrementAndGet(); running.put(parameters,token);
        UpdateRuntime.engine(this).whenComplete((engine,failure)->main.post(()-> {
            if(!running.containsKey(parameters)) return;
            if(failure!=null) { running.remove(parameters); finish(parameters,parameters.getExtras().getBoolean("localTrigger")); return; }
            if(parameters.getExtras().getBoolean("localTrigger")) {
                engine.localHint(saved-> {
                    if(running.remove(parameters)!=null) finish(parameters,!saved);
                }); return;
            }
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
    private void finish(JobParameters parameters,boolean retry) {
        // jobFinished asynchronously informs Android. Keep the started marker until
        // the next enqueue, so a late hint cannot coalesce with a completing job.
        jobFinished(parameters,retry);
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Long token=running.remove(parameters);
        if(token!=null && !parameters.getExtras().getBoolean("localTrigger") && parameters.getExtras().getString("pushEvent")==null)
            UpdateRuntime.engine(this).thenAccept(engine->engine.stopJob(token));
        return true; // System/constraint interruption, not a new automatic network retry budget.
    }
}
