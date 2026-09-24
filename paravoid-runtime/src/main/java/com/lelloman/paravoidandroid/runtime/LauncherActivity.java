package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Shell-owned launcher; application navigation belongs to the payload Activity. */
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            ((ShellApplication) getApplication()).requirePayloadLoader();
            String activity = ((ShellApplication) getApplication()).getPayloadActivity();
            Intent launch = new Intent(getIntent());
            launch.setClassName(this, activity);
            launch.setFlags(0);
            startActivity(launch);
            finish();
        } catch (Exception | LinkageError failure) {
            if (((ShellApplication) getApplication()).completeUnavailable()) {
                startActivity(new Intent(this, CrashRecovery.instance != null ? CrashRecoveryActivity.class :
                    com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity.class));
                finish();
                return;
            }
            TextView error = new TextView(this);
            error.setText("Unable to initialize application: " + failure.getMessage());
            setContentView(error);
        }
    }
}
