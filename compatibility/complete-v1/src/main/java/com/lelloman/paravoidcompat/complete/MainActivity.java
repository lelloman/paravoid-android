package com.lelloman.paravoidcompat.complete;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
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
            TextView view = new TextView(this); view.setText(text); setContentView(view);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String read(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    }
}
