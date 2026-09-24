package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

/** Framework-only UI in the shell process; never links or loads payload classes. */
public final class CrashRecoveryActivity extends Activity {
    private CrashRecovery recovery;
    private RecoveryCoordinator coordinator;
    private TextView status;
    private Button check, update, cancel, restart;
    private boolean busy;
    private java.util.function.Consumer<RecoveryCoordinator.State> listener;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        recovery=CrashRecovery.instance;
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density); layout.setPadding(padding,padding,padding,padding);
        layout.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(padding+bars.left,padding+bars.top,padding+bars.right,padding+bars.bottom); return insets;
        });
        ScrollView scroll=new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        boolean hasCrash=false;
        try { hasCrash=recovery!=null && recovery.records.pending("unknown"); } catch(java.io.IOException ignored) { }
        TextView title=new TextView(this); title.setText(hasCrash ? "App recovery" : "App updates"); title.setTextSize(26); layout.addView(title);
        TextView explanation=new TextView(this); explanation.setText(hasCrash ? "The app could not continue. You can check for an update or try opening it again." : "Check for updates and restart to apply them."); layout.addView(explanation);
        String details=recovery==null ? "Recovery could not initialize." : recovery.records.details();
        button(layout,"Crash details",v -> new AlertDialog.Builder(this).setTitle("Crash details").setMessage(details)
            .setPositiveButton("Close",null).setNeutralButton("Copy",(d,w) -> {
                ClipboardManager clipboard=getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText("App crash",details));
            }).show());
        status=new TextView(this); layout.addView(status);
        check=button(layout,"Check again",v -> coordinator.check(true));
        update=button(layout,"Update",v -> coordinator.update());
        cancel=button(layout,"Cancel",v -> coordinator.cancel());
        restart=button(layout,"Restart",v -> confirmRestart());
        button(layout,"Close",v -> { if(coordinator!=null) coordinator.cancel(); finishAndRemoveTask(); });
        coordinator=recovery==null ? null : recovery.coordinator;
        if(coordinator==null) {
            status.setText("Update service could not initialize. Install a repaired shell to recover.");
            check.setEnabled(false); update.setVisibility(View.GONE); cancel.setVisibility(View.GONE);
            restart.setEnabled(recovery!=null);
        }
    }
    private Button button(LinearLayout layout,String text,View.OnClickListener listener) {
        Button button=new Button(this); button.setText(text); button.setOnClickListener(listener); layout.addView(button); return button;
    }
    @Override protected void onStart() {
        super.onStart();
        if(coordinator!=null) {
            listener=s -> {
                busy=s.busy; status.setText(s.message); check.setEnabled(!s.busy);
                update.setVisibility(s.offer!=null ? View.VISIBLE : View.GONE); update.setEnabled(!s.busy);
                cancel.setVisibility(s.busy && !s.staging ? View.VISIBLE : View.GONE);
                restart.setText(s.ready ? "Restart" : "Retry app"); restart.setEnabled(!s.busy);
            };
            coordinator.observe(listener);
            coordinator.start();
        }
    }
    @Override protected void onStop() {
        if(coordinator!=null) { coordinator.detach(listener,!isChangingConfigurations()); }
        super.onStop();
    }
    private void confirmRestart() {
        if(busy || recovery==null) return;
        new AlertDialog.Builder(this).setTitle("Restart app?")
            .setMessage("This stops all app processes. Unsaved work may be lost. Without a repaired update, the app may crash again.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restart",(d,w) -> {
                restart.setEnabled(false);
                recovery.restart(ok -> { if(ok) finish(); else { restart.setEnabled(true); status.setText("Could not restart. Please try again."); } });
            }).show();
    }
}
