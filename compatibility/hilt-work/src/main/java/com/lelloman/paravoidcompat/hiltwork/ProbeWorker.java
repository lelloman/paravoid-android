package com.lelloman.paravoidcompat.hiltwork;

import android.content.Context;
import android.os.Process;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import dalvik.system.InMemoryDexClassLoader;

@HiltWorker
public final class ProbeWorker extends Worker {
    private final Repository repository;
    // No two-argument constructor: default reflective creation cannot mask failed injection.
    @AssistedInject public ProbeWorker(@Assisted Context context, @Assisted WorkerParameters parameters,
                                      Repository repository) {
        super(context, parameters);
        this.repository = repository;
    }
    @Override public Result doWork() {
        String token = getInputData().getString("token");
        try (ProbeDatabase db = repository.open()) {
            if (!ProbeApplication.ready || ProbeApplication.activityCreated || ProbeApplication.configurationCalls != 1
                    || repository != ProbeApplication.current.repository) {
                throw new IllegalStateException("Worker must use cold Application graph/configuration, without an Activity");
            }
            ClassLoader loader = getClass().getClassLoader();
            if ((loader instanceof InMemoryDexClassLoader) != getApplicationContext().getPackageName().endsWith(".paravoid")) {
                throw new IllegalStateException("Unexpected worker loader");
            }
            if (loader instanceof InMemoryDexClassLoader) {
                try { loader.getParent().loadClass(getClass().getName()); throw new IllegalStateException("Worker leaked into shell"); }
                catch (ClassNotFoundException expected) { }
            }
            ProbeDatabase.Row before = db.rows().find(token);
            if (before == null || before.writerPid == Process.myPid() || before.graph.equals(repository.graph)) {
                throw new IllegalStateException("Worker must read previous process's row using new injected graph");
            }
            if (db.rows().complete(token, Process.myPid(), repository.graph) != 1
                    || db.rows().find(token).workerPid != Process.myPid()) throw new IllegalStateException("Room commit failed");
            repository.record("workerPid", Integer.toString(Process.myPid()));
            repository.record("workerGraph", repository.graph);
            repository.record("completed", token);
            return Result.success();
        } catch (Exception error) {
            repository.record("error", token + ": " + error);
            android.util.Log.e("HiltWorkProbe", "Worker probe failed", error);
            return Result.failure();
        }
    }
}
