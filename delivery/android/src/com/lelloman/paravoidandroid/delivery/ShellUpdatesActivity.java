package com.lelloman.paravoidandroid.delivery;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Shell-only screen. C must route here without loading payload classes or obtaining a lease. */
public final class ShellUpdatesActivity extends Activity {
    private static volatile DeliveryController installedController;
    private DeliveryController controller;
    private LinearLayout content;
    private TextView status;
    private CheckBox checks, downloads, unmetered;
    private Spinner retention;
    private Button retryGeneration;
    private boolean rendering;
    private LifecycleSnapshot lastLifecycle;

    /** Shell bootstrap calls this in the recovery process; never obtain it from payload code. */
    public static void installController(DeliveryController controller) { installedController = controller; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("App updates");
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content); setContentView(scroll);
        status = new TextView(this); content.addView(status);
        controller = installedController;
        if (controller == null) {
            status.setText("Update controls are unavailable. Close and reopen the app, or obtain a repair shell APK from your distributor.");
            return;
        }
        button("Check now", view -> controller.checkNow());
        button("Retry update access", view -> controller.retry());
        button("Cancel download", view -> controller.cancelDownload());
        checks = checkbox("Automatically check for updates");
        downloads = checkbox("Automatically download updates");
        unmetered = checkbox("Automatic downloads only on unmetered networks");
        TextView retentionLabel = new TextView(this);
        retentionLabel.setText("Previous generations to retain for diagnostics (not rollback)"); content.addView(retentionLabel);
        retention = new Spinner(this);
        retention.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"0", "1", "2", "3"}));
        content.addView(retention);
        retention.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!rendering && lastLifecycle != null && position != lastLifecycle.retainedPrevious) controller.retainedPrevious(position);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        retryGeneration = button("Retry this quarantined generation…", view -> confirmGenerationRetry());
        retryGeneration.setVisibility(View.GONE);
    }

    @Override protected void onStart() {
        super.onStart();
        if (controller != null) controller.listen(snapshot -> runOnUiThread(() -> render(snapshot)));
    }
    @Override protected void onStop() {
        if (controller != null) controller.listen(null);
        super.onStop();
    }
    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(action); content.addView(button); return button;
    }
    private CheckBox checkbox(String label) {
        CheckBox box = new CheckBox(this); box.setText(label); content.addView(box);
        box.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) controller.preferences(new DeliveryPreferences(checks.isChecked(), downloads.isChecked(), unmetered.isChecked()));
        });
        return box;
    }
    private void render(DeliveryController.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        rendering = true;
        try {
            lastLifecycle = snapshot.lifecycle;
            StringBuilder text = new StringBuilder();
            if (lastLifecycle == null) text.append("App state unavailable.\n");
            else {
                switch (lastLifecycle.availability) {
                    case EMPTY: text.append("This app needs its first download before it can run.\n"); break;
                    case RECOVERY: text.append("App recovery is required. Check for a newer repair release or obtain a repair shell APK.\n"); break;
                    case TRIAL: text.append("The selected app generation is still awaiting startup health confirmation.\n"); break;
                    case RUNNABLE: text.append("A local app generation is available.\n"); break;
                }
                text.append("Current: ").append(identity(lastLifecycle.active)).append("\nPending: ")
                        .append(identity(lastLifecycle.pending)).append("\nPayload storage: ")
                        .append(lastLifecycle.storageBytes / (1024 * 1024)).append(" MiB\n");
                if (lastLifecycle.pending != null) text.append("Ready; activation waits for a coordinated cold start. Close and relaunch the app. Ongoing app services may delay activation.\n");
                if (lastLifecycle.error != null) text.append("App state error: ").append(lastLifecycle.error.name()).append('\n');
                retention.setSelection(lastLifecycle.retainedPrevious);
            }
            text.append("Update: ").append(snapshot.activity.name()).append('\n');
            if (snapshot.errorCode != null) {
                if (snapshot.errorCode.equals("CREDENTIAL_UNAVAILABLE"))
                    text.append("Update access unavailable. Retry, or obtain a newly authorized shell APK. A usable local app remains available offline.\n");
                else if (snapshot.errorCode.equals("CLOCK_INVALID")) text.append("Check device time before retrying.\n");
                else text.append("Update status: ").append(snapshot.errorCode).append('\n');
            }
            status.setText(text.toString());
            checks.setChecked(snapshot.preferences.automaticChecks);
            downloads.setChecked(snapshot.preferences.automaticDownloads);
            unmetered.setChecked(snapshot.preferences.unmeteredOnly);
            retryGeneration.setVisibility(lastLifecycle != null && lastLifecycle.availability == Availability.RECOVERY
                    && lastLifecycle.active != null ? View.VISIBLE : View.GONE);
        } finally { rendering = false; }
    }
    private void confirmGenerationRetry() {
        if (lastLifecycle == null || lastLifecycle.availability != Availability.RECOVERY || lastLifecycle.active == null) return;
        ExpectedArchive captured = lastLifecycle.active;
        new AlertDialog.Builder(this).setTitle("Retry quarantined generation?")
                .setMessage("Retry " + identity(captured) + " on a later cold start? It previously failed to start and may fail again. This does not restore older app data.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Retry this generation", (dialog, which) -> controller.retryQuarantinedAfterConfirmation(captured)).show();
    }
    private static String identity(ExpectedArchive release) {
        return release == null ? "none" : release.releaseId + " (payload " + release.payloadVersion + ")";
    }
}
