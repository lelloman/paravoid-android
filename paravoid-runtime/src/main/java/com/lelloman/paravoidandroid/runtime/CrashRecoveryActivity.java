package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.lelloman.paravoidandroid.recovery.RecoveryAction;
import com.lelloman.paravoidandroid.recovery.RecoveryActionResult;
import com.lelloman.paravoidandroid.recovery.RecoveryReport;
import java.util.ArrayList;
import java.util.List;

/** Framework-only UI in the shell process; never links or loads payload classes. */
public final class CrashRecoveryActivity extends Activity {
    private CrashRecovery recovery;
    private RecoveryCoordinator coordinator;
    private RecoveryActionRunner actionRunner;
    private RecoveryReport report;
    private TextView status, actionStatus;
    private Button check, update, cancel, restart;
    private final List<Button> actionButtons=new ArrayList<>();
    private boolean busy;
    private java.util.function.Consumer<RecoveryCoordinator.State> listener;
    private final RecoveryActionRunner.Observer actionObserver=this::actionChanged;
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
        TextView title=new TextView(this); title.setText(tr(hasCrash ? "App recovery" : "App updates")); title.setTextSize(26); layout.addView(title);
        TextView explanation=new TextView(this); explanation.setText(tr(hasCrash ? "The app could not continue. You can check for an update or try opening it again." : "Check for updates and restart to apply them.")); layout.addView(explanation);
        String latest=null, details;
        if(recovery==null) details=tr("Recovery could not initialize.");
        else try { latest=recovery.records.latest(); details=latest==null ? tr("No crash report recorded.") : latest; }
        catch(java.io.IOException unreadable) { details=tr("Crash details could not be read."); }
        String shownDetails=details;
        button(layout,tr("Crash details"),v -> new AlertDialog.Builder(this).setTitle(tr("Crash details")).setMessage(shownDetails)
            .setPositiveButton(tr("Close"),null).setNeutralButton(tr("Copy"),(d,w) -> {
                ClipboardManager clipboard=getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText("App crash",shownDetails));
            }).show());
        actionRunner=recovery==null ? null : RecoveryActionRunner.get(this);
        if(actionRunner!=null) {
            java.io.File directory=new java.io.File(getNoBackupFilesDir(),"paravoid-recovery-actions");
            if(!directory.isDirectory() && !directory.mkdirs()) android.util.Log.w("ParavoidAndroid","Recovery action directory unavailable");
            report=new RecoveryReport(getPackageName(),recovery.shellVersion,
                getResources().getConfiguration().getLocales().get(0),hasCrash,latest,directory);
            for(RecoveryAction action:actionRunner.actions(report))
                actionButtons.add(button(layout,action.label,v -> confirmAction(action)));
            actionStatus=new TextView(this); actionStatus.setVisibility(View.GONE);
            actionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); layout.addView(actionStatus);
        }
        status=new TextView(this); layout.addView(status);
        check=button(layout,tr("Check again"),v -> coordinator.check(true));
        update=button(layout,tr("Update"),v -> coordinator.update());
        cancel=button(layout,tr("Cancel"),v -> coordinator.cancel());
        restart=button(layout,tr("Restart"),v -> confirmRestart());
        button(layout,tr("Close"),v -> { if(coordinator!=null) coordinator.cancel(); finishAndRemoveTask(); });
        coordinator=recovery==null ? null : recovery.coordinator;
        if(coordinator==null) {
            status.setText(tr("Update service could not initialize. Install a repaired shell to recover."));
            check.setEnabled(false); update.setVisibility(View.GONE); cancel.setVisibility(View.GONE);
            restart.setEnabled(recovery!=null);
        }
    }
    private String tr(String english,Object... args) { return ShellText.tr(this,english,args); }
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
                restart.setText(tr(s.ready ? "Restart" : "Retry app")); restart.setEnabled(!s.busy);
            };
            coordinator.observe(listener);
            coordinator.start();
        }
        if(actionRunner!=null) actionRunner.observe(actionObserver);
    }
    @Override protected void onStop() {
        if(coordinator!=null) { coordinator.detach(listener,!isChangingConfigurations()); }
        if(actionRunner!=null) actionRunner.detach(actionObserver,!isChangingConfigurations());
        super.onStop();
    }
    private void confirmAction(RecoveryAction action) {
        if(actionRunner.running()) return;
        if(action.confirmation==null || action.confirmation.isEmpty()) { actionRunner.perform(action,report); return; }
        new AlertDialog.Builder(this).setTitle(action.label).setMessage(action.confirmation)
            .setNegativeButton(tr("Cancel"),null)
            .setPositiveButton(tr("Continue"),(d,w) -> actionRunner.perform(action,report)).show();
    }
    private void actionChanged(String running,RecoveryActionRunner.Outcome outcome) {
        for(Button button:actionButtons) button.setEnabled(running==null);
        String text=running!=null ? tr("Working…") : outcome!=null && outcome.failed ? tr("The action could not be completed. Please try again.") : null;
        actionStatus.setText(text); actionStatus.setVisibility(text==null ? View.GONE : View.VISIBLE);
        if(outcome==null || outcome.failed) return;
        RecoveryActionResult result=outcome.result;
        try {
            switch(result.kind) {
                case MESSAGE:
                    new AlertDialog.Builder(this).setMessage(result.text).setPositiveButton(tr("OK"),null).show();
                    break;
                case OPEN_URI:
                    Uri uri=Uri.parse(result.target);
                    startActivity(new Intent("mailto".equalsIgnoreCase(uri.getScheme()) ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW,uri));
                    break;
                case SHARE_TEXT:
                    Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,result.text);
                    if(result.target!=null) share.putExtra(Intent.EXTRA_SUBJECT,result.target);
                    startActivity(Intent.createChooser(share,null));
                    break;
                case NONE:
                    break;
            }
        } catch(ActivityNotFoundException unavailable) {
            actionStatus.setText(tr("No app on this device can open this.")); actionStatus.setVisibility(View.VISIBLE);
        }
    }
    private void confirmRestart() {
        if(busy || recovery==null) return;
        new AlertDialog.Builder(this).setTitle(tr("Restart app?"))
            .setMessage(tr("This stops all app processes. Unsaved work may be lost. Without a repaired update, the app may crash again."))
            .setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Restart"),(d,w) -> {
                restart.setEnabled(false);
                recovery.restart(ok -> { if(ok) finish(); else { restart.setEnabled(true); status.setText(tr("Could not restart. Please try again.")); } });
            }).show();
    }
}
