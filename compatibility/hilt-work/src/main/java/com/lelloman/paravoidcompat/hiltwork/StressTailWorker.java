package com.lelloman.paravoidcompat.hiltwork;

import android.content.Context;
import androidx.work.*;
import dalvik.system.InMemoryDexClassLoader;

/** Intentionally not @HiltWorker: exercises HiltWorkerFactory's reflective fallback. */
public final class StressTailWorker extends Worker {
    public StressTailWorker(Context context, WorkerParameters parameters) { super(context, parameters); }
    @Override public Result doWork() {
        String token = getInputData().getString("run");
        try {
            String value = getInputData().getString("value");
            if (token == null || !("chain-✓-" + token).equals(value)) throw new IllegalStateException("Missing predecessor Data");
            if ((getClass().getClassLoader() instanceof InMemoryDexClassLoader)
                    != getApplicationContext().getPackageName().endsWith(".paravoid")) throw new IllegalStateException("Wrong fallback loader");
            StressControl.record(getApplicationContext(), "tail", StressControl.event(token).put("value", value)
                .put("id", getId().toString()));
            return Result.success(new Data.Builder().putString("value", value).build());
        } catch (Exception error) {
            try { StressControl.record(getApplicationContext(), "error", StressControl.event(token).put("error", error.toString())); }
            catch (Exception ignored) { android.util.Log.e("WorkStress", "Cannot report error", ignored); }
            return Result.failure();
        }
    }
}
