package com.lelloman.voidandroid.sample;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** Ordinary Android Activity: no VoidAndroid superclass, annotations, or entry API. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (SampleApplication.initializationCount != 1) {
            throw new IllegalStateException("Application must initialize once before the Activity.");
        }
        String mode = "Application initialized: " + SampleApplication.initializationCount;
        View root = new CounterScreen().createView(this, mode);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(view.getPaddingLeft(), insets.getSystemWindowInsetTop() + 24,
                view.getPaddingRight(), insets.getSystemWindowInsetBottom() + 24);
            return insets;
        });
        root.requestApplyInsets();
    }
}
