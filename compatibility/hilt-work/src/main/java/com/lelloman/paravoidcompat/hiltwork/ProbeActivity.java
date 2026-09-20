package com.lelloman.paravoidcompat.hiltwork;

import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.work.*;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProbeActivity extends ComponentActivity {
    @Inject Repository repository;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ProbeApplication.activityCreated = true;
        setContentView(new TextView(this));
        String token = getIntent().getStringExtra("probeRun");
        if (token == null) return;
        new Thread(() -> {
            try (ProbeDatabase db = repository.open()) {
                if (repository != ProbeApplication.current.repository) throw new IllegalStateException("Different singleton");
                if (getIntent().getBooleanExtra("verify", false)) {
                    ProbeDatabase.Row row = db.rows().find(token);
                    if (row == null || row.workerPid == 0 || row.workerPid == Process.myPid()
                            || row.writerPid == Process.myPid() || row.graph.equals(repository.graph)) {
                        throw new IllegalStateException("Expected committed worker row in third process/graph");
                    }
                    repository.record("verifiedPid", Integer.toString(Process.myPid()));
                    repository.record("verified", token);
                } else {
                    db.rows().insert(new ProbeDatabase.Row(token, Process.myPid(), 0, repository.graph));
                    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ProbeWorker.class)
                        .setInputData(new Data.Builder().putString("token", token).build())
                        .setInitialDelay(20, TimeUnit.SECONDS).build();
                    WorkManager.getInstance(this).enqueue(work).getResult().get();
                    repository.record("writerGraph", repository.graph);
                    repository.record("enqueued", token);
                }
            } catch (Exception error) {
                repository.record("error", token + ": " + error);
                android.util.Log.e("HiltWorkProbe", "Activity probe failed", error);
            }
        }, "hilt-work-probe").start();
    }
}
