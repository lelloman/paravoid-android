package com.lelloman.paravoidcompat.resourcesplit;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.TypedValue;
import android.util.Xml;
import android.widget.TextView;
import com.lelloman.paravoidcompat.resourcesplit.linked.R;
import java.util.Locale;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    static String title(Context context) { return value(context, R.string.title); }
    static String value(Context context, int id) {
        try { return context.getString(id); } catch (Resources.NotFoundException expected) { return "absent"; }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String title = title(this);
            boolean present = !title.equals("absent");
            TypedValue pinned = new TypedValue();
            boolean pinnedTheme = getTheme().resolveAttribute(android.R.attr.colorAccent, pinned, true)
                && pinned.data == 0xff774400;
            JSONObject result = new JSONObject().put("run", getIntent().getStringExtra("run"))
                .put("pid", android.os.Process.myPid()).put("title", title)
                .put("shellLabel", getString(R.string.shell_label)).put("pinnedTheme", pinnedTheme)
                .put("titleId", R.string.title).put("constructorTitle", ProbeApplication.constructorTitle)
                .put("shell", getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader)
                .put("libraryLoader", com.lelloman.paravoidcompat.resourcesplit.library.R.class.getClassLoader()
                    == getClass().getClassLoader())
                .put("removed", value(this, R.string.removed));
            int added = getResources().getIdentifier("a_added", "string", getPackageName());
            result.put("added", added == 0 ? "absent" : value(this, added));
            if (present) {
                setTheme(R.style.PayloadTheme);
                TextView panel = (TextView) getLayoutInflater().inflate(R.layout.panel, null);
                setContentView(panel);
                result.put("view", panel.getText());
                result.put("library", getString(com.lelloman.paravoidcompat.resourcesplit.library.R.string.library_message));
                try (XmlResourceParser parser = getResources().getLayout(R.layout.panel)) {
                    while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {}
                    TypedArray attrs = obtainStyledAttributes(Xml.asAttributeSet(parser),
                        com.lelloman.paravoidcompat.resourcesplit.library.R.styleable.ProbePanel);
                    try { result.put("styleable", attrs.getString(
                        com.lelloman.paravoidcompat.resourcesplit.library.R.styleable.ProbePanel_probeLabel)); }
                    finally { attrs.recycle(); }
                }
                Configuration config = new Configuration(getResources().getConfiguration());
                config.setLocale(Locale.ITALIAN);
                result.put("italian", createConfigurationContext(config).getString(R.string.title));
                TypedValue accent = new TypedValue();
                result.put("payloadTheme", getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)
                    && accent.data == getColor(R.color.payload_accent));
                result.put("accent", getColor(R.color.payload_accent));
            } else {
                TextView label = new TextView(this); label.setText("Pinned-only shell"); setContentView(label);
            }
            getSharedPreferences("split-probe", 0).edit().putString("report", result.toString()).commit();
        } catch (Exception failure) {
            getSharedPreferences("split-probe", 0).edit().putString("error", failure.toString()).commit();
            throw new IllegalStateException(failure);
        }
    }
}
