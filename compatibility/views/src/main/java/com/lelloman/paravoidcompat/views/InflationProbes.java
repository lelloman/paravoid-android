package com.lelloman.paravoidcompat.views;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import org.json.JSONObject;

final class InflationProbes {
    static void run(Context activity, JSONObject results) throws Exception {
        Context application = activity.getApplicationContext();
        inflate("inflater.application", application, results);
        inflate("inflater.themedApplication", new ContextThemeWrapper(application, R.style.ProbeTheme), results);
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.setLocale(java.util.Locale.ITALIAN);
        inflate("inflater.configuredApplication", application.createConfigurationContext(config), results);
        inflate("inflater.configuredActivity", activity.createConfigurationContext(config), results);
    }
    private static void inflate(String name, Context context, JSONObject results) throws Exception {
        try {
            StatefulView view = (StatefulView) LayoutInflater.from(context).inflate(R.layout.inflation_probe, null);
            if (view.value != 19 || view.getClass().getClassLoader() != InflationProbes.class.getClassLoader()) {
                throw new AssertionError("Wrong XML attribute or defining loader");
            }
            results.put(name, "PASS");
        } catch (RuntimeException | LinkageError | AssertionError error) {
            android.util.Log.e("ViewsProbe", name, error);
            results.put(name, error.toString());
        }
    }
}
