package com.lelloman.paravoidcompat.hilt;

import android.os.Bundle;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProbeActivity extends ComponentActivity {
    @Inject public ProbeService service;
    @Inject public ActivityToken token;
    public ProbeViewModel model;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        model = new ViewModelProvider(this).get(ProbeViewModel.class);
        if (service == null || service != ProbeApplication.initializedService) {
            throw new IllegalStateException("Application and Activity must share the injected singleton");
        }
        if (token == null || model.service != service) throw new IllegalStateException("Hilt scopes were not initialized");
        TextView status = new TextView(this);
        status.setText("Hilt Application + Activity injection: OK");
        status.setTag("hilt-status");
        setContentView(status);
    }
}
