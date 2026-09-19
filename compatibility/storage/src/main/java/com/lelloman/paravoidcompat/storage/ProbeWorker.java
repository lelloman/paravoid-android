package com.lelloman.paravoidcompat.storage;

import android.content.Context;
import android.os.Process;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import dalvik.system.InMemoryDexClassLoader;

public final class ProbeWorker extends Worker {
    public ProbeWorker(Context context, WorkerParameters parameters) { super(context, parameters); }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        String token = getInputData().getString("token");
        try (ProbeDatabase db = ProbeDatabase.open(context)) {
            if ((getClass().getClassLoader() instanceof InMemoryDexClassLoader) != context.getPackageName().endsWith(".paravoid")) {
                throw new IllegalStateException("Worker must use expected loader");
            }
            ProbeDatabase.Row before = db.rows().find(token);
            if (before == null || before.writerPid == Process.myPid()) {
                throw new IllegalStateException("Worker must read the previous process's persisted row");
            }
            if (db.rows().complete(token, Process.myPid()) != 1) throw new IllegalStateException("Room update failed");
            ProbeDatabase.Row after = db.rows().find(token);
            if (after.workerPid != Process.myPid()) throw new IllegalStateException("Room readback failed");
            if (!context.getSharedPreferences("storage-probe", Context.MODE_PRIVATE).edit()
                    .putString("completed", token).putInt("workerPid", Process.myPid()).commit()) {
                throw new IllegalStateException("Cannot save worker result");
            }
            return Result.success();
        } catch (Exception error) {
            android.util.Log.e("StorageProbe", "Worker failed", error);
            return Result.failure();
        }
    }
}
