package com.lelloman.paravoidcompat.hiltwork;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import androidx.work.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixture-only control surface. Uses public WorkManager APIs, not test schedulers. */
public final class StressControl extends BroadcastReceiver {
    static void record(Context context, String key, JSONObject value) {
        if (!context.getSharedPreferences("work-stress", Context.MODE_PRIVATE).edit()
                .putString(key, value.toString()).commit()) throw new IllegalStateException("Observation write failed");
    }
    static JSONObject event(String token) throws Exception {
        return new JSONObject().put("run", token).put("pid", Process.myPid())
            .put("graph", ProbeApplication.current.repository.graph);
    }
    @Override public void onReceive(Context context, Intent intent) {
        String token = intent.getStringExtra("probeRun");
        String command = intent.getStringExtra("command");
        String request = intent.getStringExtra("request");
        if (token == null || command == null || request == null) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                WorkManager manager = WorkManager.getInstance(context);
                String name = "stress-" + token;
                if (command.equals("retry") || command.equals("hold")) {
                    OneTimeWorkRequest root = new OneTimeWorkRequest.Builder(StressWorker.class)
                        .setInputData(new Data.Builder().putString("run", token).putString("behavior", command).build())
                        .setInitialDelay(20, TimeUnit.SECONDS)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build();
                    OneTimeWorkRequest tail = new OneTimeWorkRequest.Builder(StressTailWorker.class).build();
                    manager.beginUniqueWork(name, ExistingWorkPolicy.KEEP, root).then(tail).enqueue().getResult().get();
                    record(context, "enqueued", event(token).put("root", root.getId().toString())
                        .put("tail", tail.getId().toString()).put("request", request));
                } else if (command.equals("cancel")) {
                    manager.cancelUniqueWork(name).getResult().get();
                } else if (!command.equals("snapshot")) {
                    throw new IllegalArgumentException("Unknown stress command");
                }
                JSONArray work = new JSONArray();
                for (WorkInfo info : manager.getWorkInfosForUniqueWork(name).get()) {
                    work.put(new JSONObject().put("id", info.getId().toString()).put("state", info.getState().name())
                        .put("attempt", info.getRunAttemptCount()).put("next", info.getNextScheduleTimeMillis())
                        .put("output", info.getOutputData().getString("value")));
                }
                record(context, "snapshot", event(token).put("request", request).put("work", work));
            } catch (Exception error) {
                try { record(context, "error", event(token).put("error", error.toString())); }
                catch (Exception ignored) { android.util.Log.e("WorkStress", "Cannot report error", ignored); }
                android.util.Log.e("WorkStress", "Control failed", error);
            } finally { pending.finish(); }
        }, "work-stress-control").start();
    }
}
