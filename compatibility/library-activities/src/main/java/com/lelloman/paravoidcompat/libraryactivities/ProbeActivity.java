package com.lelloman.paravoidcompat.libraryactivities;

public final class ProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("Real AppAuth and Androidoscopy dependencies packaged");
        setContentView(label);
    }
}
