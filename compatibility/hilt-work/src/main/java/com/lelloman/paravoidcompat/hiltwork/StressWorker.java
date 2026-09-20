package com.lelloman.paravoidcompat.hiltwork;

import android.content.Context;
import androidx.hilt.work.HiltWorker;
import androidx.work.*;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@HiltWorker
public final class StressWorker extends Worker {
    private final Repository repository;
    private final CountDownLatch stopped = new CountDownLatch(1);
    @AssistedInject public StressWorker(@Assisted Context context, @Assisted WorkerParameters parameters,
                                       Repository repository) {
        super(context, parameters);
        this.repository = repository;
    }
    @Override public Result doWork() {
        String token = getInputData().getString("run");
        try {
            if (!ProbeApplication.ready || ProbeApplication.activityCreated
                    || ProbeApplication.configurationCalls != 1 || repository != ProbeApplication.current.repository
                    || getApplicationContext() != repository.context) throw new IllegalStateException("Cold injected graph/context mismatch");
            StressControl.record(getApplicationContext(), "attempt" + getRunAttemptCount(),
                StressControl.event(token).put("attempt", getRunAttemptCount()).put("id", getId().toString()));
            if ("hold".equals(getInputData().getString("behavior"))) {
                if (!stopped.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("Cancellation never arrived");
                return Result.failure(); // Ignored once cancelled; never lets the dependent run.
            }
            if (getRunAttemptCount() == 0) return Result.retry();
            if (getRunAttemptCount() != 1) throw new IllegalStateException("Unexpected retry count");
            return Result.success(new Data.Builder().putString("run", token).putString("value", "chain-✓-" + token).build());
        } catch (Exception error) {
            // Cancellation may interrupt doWork; onStopped independently proves cancellation.
            if (!isStopped()) {
                try { StressControl.record(getApplicationContext(), "error", StressControl.event(token).put("error", error.toString())); }
                catch (Exception ignored) { android.util.Log.e("WorkStress", "Cannot report error", ignored); }
            }
            return Result.failure();
        }
    }
    @Override public void onStopped() {
        try {
            StressControl.record(getApplicationContext(), "stopped", StressControl.event(getInputData().getString("run"))
                .put("isStopped", isStopped()).put("id", getId().toString()));
        } catch (Exception error) { android.util.Log.e("WorkStress", "Stop reporting failed", error); }
        finally { stopped.countDown(); }
    }
}
