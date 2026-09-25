package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.os.Bundle;
import android.widget.TextView;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/** Visible shell coordinator survives the payload processes it stops. */
public final class RestartActivity extends Activity {
    static final String PREFERENCES="paravoid-restart-v1";
    private boolean started;
    private ExpectedArchive offer;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        offer=UpdateWire.offer(getIntent().getBundleExtra("offer"));
        TextView status=new TextView(this); status.setPadding(32,32,32,32); status.setText("Preparing to restart…"); setContentView(status);
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        if(started) return;
        started=true;
        if(offer!=null && !ShellApplication.requireInstance().pendingUpdateMatches(offer)) { finish(); return; }
        if(offer!=null && getIntent().getBooleanExtra("policy",false)
                && offer.archiveSha256.equals(getSharedPreferences(PREFERENCES,0).getString("handled",""))) { finish(); return; }
        if(getIntent().getBooleanExtra("confirmation",false)) {
            new AlertDialog.Builder(this).setTitle("Restart app?")
                .setMessage("This stops ongoing app work and may discard unsaved changes. A ready update will be applied on restart.")
                .setPositiveButton("Restart",(dialog,which)->restart())
                .setNegativeButton("Not now",(dialog,which)-> { remember(); finish(); })
                .setOnCancelListener(dialog-> { remember(); finish(); }).show();
        } else restart();
    }
    private boolean remember() {
        return offer==null || !getIntent().getBooleanExtra("policy",false)
            || getSharedPreferences(PREFERENCES,0).edit().putString("handled",offer.archiveSha256).commit();
    }
    private void restart() {
        if(offer!=null && !ShellApplication.requireInstance().pendingUpdateMatches(offer)) { finish(); return; }
        // Persist before stopping processes so a failed activation cannot form an automatic restart loop.
        if(!remember()) { failed(); return; }
        java.util.function.Consumer<Boolean> result=success-> { if(success) finish(); else failed(); };
        if(CrashRecovery.instance!=null) CrashRecovery.instance.restart(result);
        else ShellRestart.restart(getApplication(),result);
    }
    private void failed() {
        if(isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Restart unavailable")
            .setMessage("The shell could not complete the restart. You can try again from the app’s update controls.")
            .setPositiveButton("Close",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show();
    }
}
