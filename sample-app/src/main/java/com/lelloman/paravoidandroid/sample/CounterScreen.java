package com.lelloman.paravoidandroid.sample;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The exact same implementation runs as bundled DEX and as ordinary APK classes. */
public final class CounterScreen {
    public View createView(Activity activity, String executionMode) {
        SharedPreferences state = activity.getSharedPreferences("sample", Context.MODE_PRIVATE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 250));
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(activity);
        title.setText("Paravoid Android");
        title.setTextSize(30);
        title.setTextColor(Color.BLACK);
        root.addView(title);
        TextView mode = new TextView(activity);
        mode.setText(executionMode + "\n" + getClass().getClassLoader().getClass().getSimpleName());
        mode.setTextColor(Color.DKGRAY);
        root.addView(mode);
        TextView counter = new TextView(activity);
        counter.setTag("counter");
        counter.setTextSize(24);
        counter.setTextColor(Color.BLACK);
        counter.setText("Count: " + state.getInt("count", 0));
        root.addView(counter);
        Button increment = new Button(activity);
        increment.setTag("increment");
        increment.setText("Increment");
        increment.setOnClickListener(view -> {
            int count = state.getInt("count", 0) + 1;
            state.edit().putInt("count", count).apply();
            counter.setText("Count: " + count);
        });
        root.addView(increment);
        return root;
    }
}
