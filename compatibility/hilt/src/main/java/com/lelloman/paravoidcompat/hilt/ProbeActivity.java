package com.lelloman.paravoidcompat.hilt;

import android.os.Bundle;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProbeActivity extends ComponentActivity {
    @Inject public ProbeService service;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (service == null || service != ProbeApplication.initializedService) {
            throw new IllegalStateException("Application and Activity must share the injected singleton");
        }
        TextView status = new TextView(this);
        status.setText("Hilt Application + Activity injection: OK");
        status.setTag("hilt-status");
        setContentView(status);
    }
}
