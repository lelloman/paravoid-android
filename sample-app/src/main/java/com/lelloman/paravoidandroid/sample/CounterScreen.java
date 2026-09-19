package com.lelloman.paravoidandroid.sample;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

/** The exact same implementation runs as bundled DEX and as ordinary APK classes. */
public final class CounterScreen {
    public View createView(Activity activity, String executionMode) {
        SharedPreferences state = activity.getSharedPreferences("sample", Context.MODE_PRIVATE);
        View root = activity.getLayoutInflater().inflate(R.layout.counter_screen, null);
        TextView mode = root.findViewById(R.id.mode);
        mode.setText(executionMode + "\n" + getClass().getClassLoader().getClass().getSimpleName());
        TextView counter = root.findViewById(R.id.counter);
        counter.setText(activity.getString(R.string.counter_value, state.getInt("count", 0)));
        root.findViewById(R.id.increment).setOnClickListener(view -> {
            int count = state.getInt("count", 0) + 1;
            state.edit().putInt("count", count).apply();
            counter.setText(activity.getString(R.string.counter_value, count));
        });
        TextView report = root.findViewById(R.id.resources);
        try {
            StringBuilder details = new StringBuilder();
            ResourceProbe.snapshot(activity).forEach((name, value) -> details.append(name).append(": ").append(value).append('\n'));
            report.setText(details);
        } catch (Exception error) {
            throw new IllegalStateException("Resource/class demonstration failed", error);
        }
        return root;
    }
}
