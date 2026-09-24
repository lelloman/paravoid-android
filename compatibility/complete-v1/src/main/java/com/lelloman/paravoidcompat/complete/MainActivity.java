package com.lelloman.paravoidcompat.complete;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.lelloman.paravoidandroid.runtime.ParavoidUpdates;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private ParavoidUpdates.Subscription updateSubscription;
    private TextView updateStatus;
    public MainActivity() { StartupProbe.hit("activity-constructor"); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        StartupProbe.hit("activity-create");
        if (getIntent().getBooleanExtra("crashCreate", false)) throw new IllegalStateException("Later Activity creation fixture");
        if (!ProbeApplication.ready) throw new IllegalStateException("Application not ready");
        try {
            String asset;
            try (java.io.InputStream in = getAssets().open("probe.txt")) { asset = read(in); }
            String javaResource;
            try (java.io.InputStream in = getClassLoader().getResourceAsStream("fixture.txt")) { javaResource = read(in); }
            String text = "generation=" + getString(R.string.generation) + ";asset=" + asset + ";java=" + javaResource;
            android.content.res.Configuration configuration = new android.content.res.Configuration(getResources().getConfiguration());
            configuration.setLocale(java.util.Locale.FRENCH);
            if (!getString(R.string.generation).equals(createConfigurationContext(configuration).getString(R.string.generation)))
                throw new IllegalStateException("Configuration context lost generation resources");
            if (getIntent().getBooleanExtra("schedule", false)) {
                android.app.job.JobScheduler jobs = getSystemService(android.app.job.JobScheduler.class);
                jobs.schedule(new android.app.job.JobInfo.Builder(123,
                    new android.content.ComponentName(this, ProbeJob.class)).setMinimumLatency(86400000).build());
            }
            getSharedPreferences("probe", 0).edit().putString("activity", text).commit();
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            TextView view = new TextView(this); view.setText(text); layout.addView(view);
            updateStatus = new TextView(this); layout.addView(updateStatus);
            Button check = new Button(this); check.setText("Check for updates");
            check.setOnClickListener(clicked -> ParavoidUpdates.get().checkNow());
            layout.addView(check);
            Button controls = new Button(this); controls.setText("Update controls");
            controls.setOnClickListener(clicked -> ParavoidUpdates.get().openControls(this));
            layout.addView(controls);
            Button crash=new Button(this); crash.setText("Crash main thread");
            crash.setOnClickListener(v -> { throw new IllegalStateException("Recovery main fixture"); }); layout.addView(crash);
            Button worker=new Button(this); worker.setText("Crash worker thread");
            worker.setOnClickListener(v -> new Thread(() -> { throw new IllegalStateException("Recovery worker fixture"); },"fixture-worker").start()); layout.addView(worker);
            setContentView(layout);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public void onStart() {
        super.onStart();
        updateSubscription = ParavoidUpdates.get().observe(update -> {
            if (updateStatus == null) return;
            String label = update.updateAvailable() ? "Update available" : "Up to date";
            updateStatus.setText(label + " (" + update.phase + ")");
            getSharedPreferences("probe", 0).edit().putString("update", update.phase.name() + ";available=" + update.updateAvailable()).apply();
        });
    }
    @Override public void onStop() {
        if (updateSubscription != null) updateSubscription.close();
        updateSubscription = null;
        super.onStop();
    }
    private static String read(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    }
}
