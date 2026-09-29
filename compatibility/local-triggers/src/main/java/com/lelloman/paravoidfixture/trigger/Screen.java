package com.lelloman.paravoidfixture.trigger;
public final class Screen extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text=new android.widget.TextView(this);
        text.setText(getString(R.string.generation)); setContentView(text);
    }
}
