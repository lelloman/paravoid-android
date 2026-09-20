package com.lelloman.paravoidcompat.storage;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getSharedPreferences("storage-probe", MODE_PRIVATE).edit().putInt("activityPid", Process.myPid()).commit();
        TextView text = new TextView(this);
        text.setText("Room + WorkManager: awaiting test request");
        setContentView(text);
        String token = getIntent().getStringExtra("probeRun");
        if (token == null) return;
        new Thread(() -> {
            try (ProbeDatabase db = ProbeDatabase.open(this)) {
                if (getIntent().getBooleanExtra("verify", false)) {
                    ProbeDatabase.Row row = db.rows().find(token);
                    if (row == null || row.workerPid == 0 || row.workerPid == row.writerPid) {
                        throw new IllegalStateException("Missing independently committed worker row");
                    }
                    record("verified", token);
                } else {
                    db.rows().insert(new ProbeDatabase.Row(token, Process.myPid(), 0));
                    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ProbeWorker.class)
                        .setInputData(new Data.Builder().putString("token", token).build())
                        .setInitialDelay(20, TimeUnit.SECONDS).build();
                    WorkManager.getInstance(this).enqueue(work).getResult().get();
                    record("enqueued", token);
                }
                runOnUiThread(() -> text.setText("Room + WorkManager: " + token));
            } catch (Exception error) { throw new IllegalStateException("Storage probe failed", error); }
        }, "storage-probe").start();
    }

    private void record(String key, String token) {
        if (!getSharedPreferences("storage-probe", MODE_PRIVATE).edit().putString(key, token).commit()) {
            throw new IllegalStateException("Cannot save storage probe result");
        }
    }
}
