package com.lelloman.paravoidcompat.directboot;

import android.app.Activity;
import android.os.Bundle;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ProbeApplication.activityCreated = true;
        BootReceiver.prepare(this, getIntent().getStringExtra("probeRun"));
    }
}
