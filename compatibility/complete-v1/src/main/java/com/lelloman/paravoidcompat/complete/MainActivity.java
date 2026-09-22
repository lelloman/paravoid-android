package com.lelloman.paravoidcompat.complete;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ProbeApplication.ready) throw new IllegalStateException("Application not ready");
        try {
            String asset;
            try (java.io.InputStream in = getAssets().open("probe.txt")) { asset = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(); }
            String javaResource;
            try (java.io.InputStream in = getClassLoader().getResourceAsStream("fixture.txt")) { javaResource = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(); }
            String text = "generation=" + getString(R.string.generation) + ";asset=" + asset + ";java=" + javaResource;
            getSharedPreferences("probe", 0).edit().putString("activity", text).commit();
            TextView view = new TextView(this); view.setText(text); setContentView(view);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
