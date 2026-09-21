package com.lelloman.paravoidcompat.automaticresources;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.util.Xml;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private static int creations;
    private static final String processToken = UUID.randomUUID().toString();
    static String title(Context context) { return value(context, R.string.title); }
    static String value(Context context, int id) {
        try { return context.getString(id); } catch (Resources.NotFoundException expected) { return "absent"; }
    }
    private String named(String name) { return value(this, getResources().getIdentifier(name, "string", getPackageName())); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String title = title(this);
            boolean present = !title.equals("absent");
            boolean shell = getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader;
            TypedValue pinned = new TypedValue();
            boolean pinnedTheme = getTheme().resolveAttribute(android.R.attr.colorAccent, pinned, true) && pinned.data == 0xff774400;
            var prefs = getSharedPreferences("split-probe", 0);
            String marker = prefs.getString("marker", null);
            if (marker == null) { marker = UUID.randomUUID().toString(); prefs.edit().putString("marker", marker).commit(); }
            JSONObject result = new JSONObject().put("run", getIntent().getStringExtra("run"))
                .put("pid", android.os.Process.myPid()).put("processToken", processToken).put("creations", ++creations)
                .put("marker", marker).put("title", title).put("shell", shell)
                .put("shellLabel", getString(R.string.shell_label)).put("pinnedTheme", pinnedTheme)
                .put("titleId", R.string.title).put("constructorTitle", ProbeApplication.constructorTitle)
                .put("applicationTitle", ProbeApplication.applicationTitle).put("providerTitle", EarlyProvider.earlyTitle)
                .put("providerBeforeApplication", EarlyProvider.beforeApplication)
                .put("night", (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                .put("removed", named("removed")).put("added", named("a_added"));
            if (present) {
                setTheme(R.style.PayloadTheme);
                TextView panel = (TextView) getLayoutInflater().inflate(R.layout.panel, null);
                panel.setOnClickListener(view -> recreate());
                setContentView(panel);
                result.put("view", panel.getText());
                result.put("library", getString(com.lelloman.paravoidcompat.automaticresources.library.R.string.library_message));
                result.put("libraryLoader", com.lelloman.paravoidcompat.automaticresources.library.R.class.getClassLoader() == getClass().getClassLoader());
                try (XmlResourceParser parser = getResources().getLayout(R.layout.panel)) {
                    while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {}
                    TypedArray attrs = obtainStyledAttributes(Xml.asAttributeSet(parser), com.lelloman.paravoidcompat.automaticresources.library.R.styleable.ProbePanel);
                    try { result.put("styleable", attrs.getString(com.lelloman.paravoidcompat.automaticresources.library.R.styleable.ProbePanel_probeLabel)); }
                    finally { attrs.recycle(); }
                }
                Configuration config = new Configuration(getResources().getConfiguration());
                config.setLocale(Locale.ITALIAN);
                result.put("italian", createConfigurationContext(config).getString(R.string.title));
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
                result.put("dayAccent", createConfigurationContext(config).getColor(R.color.payload_accent));
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
                result.put("nightAccent", createConfigurationContext(config).getColor(R.color.payload_accent));
                result.put("accent", getColor(R.color.payload_accent));
                TypedValue accent = new TypedValue();
                result.put("payloadTheme", getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true) && accent.data == getColor(R.color.payload_accent));
                try (var input = getAssets().open("content.txt")) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    result.put("asset", new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim());
                }
            } else {
                TextView label = new TextView(this); label.setText("Pinned-only shell"); setContentView(label);
                try (var input = getAssets().open("content.txt")) { throw new AssertionError("Movable asset leaked into shell"); }
                catch (java.io.FileNotFoundException expected) { result.put("asset", "absent"); }
            }
            Bundle worker = getContentResolver().call(Uri.parse("content://" + getPackageName() + ".worker"), "probe", null, null);
            JSONObject workerResult = new JSONObject();
            for (String key : worker.keySet()) workerResult.put(key, worker.get(key));
            result.put("worker", workerResult);
            prefs.edit().putString("report", result.toString()).commit();
        } catch (Exception | AssertionError failure) {
            getSharedPreferences("split-probe", 0).edit().putString("error", failure.toString()).commit();
            throw new IllegalStateException(failure);
        }
    }
}
