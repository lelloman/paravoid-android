package com.lelloman.paravoidandroid.sample;

import android.content.Context;
import android.content.res.Resources;
import android.os.Parcel;
import com.lelloman.paravoidsample.library.GreetingProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import org.json.JSONObject;

/** Uses ordinary Android/Java APIs, with no dependency on the packaging runtime. */
public final class ResourceProbe {
    public static Map<String, String> snapshot(Context context) throws Exception {
        Resources resources = context.getResources();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("greeting", context.getString(R.string.greeting));
        result.put("one", resources.getQuantityString(R.plurals.items, 1, 1));
        result.put("many", resources.getQuantityString(R.plurals.items, 3, 3));
        result.put("array", String.join(",", resources.getStringArray(R.array.sample_planets)));
        result.put("integer", Integer.toString(resources.getInteger(R.integer.sample_limit)));
        result.put("boolean", Boolean.toString(resources.getBoolean(R.bool.sample_enabled)));
        result.put("paddingDp", Integer.toString(Math.round(resources.getDimension(R.dimen.screen_padding) / resources.getDisplayMetrics().density)));
        result.put("background", Integer.toHexString(resources.getColor(R.color.sample_background, context.getTheme())));
        result.put("drawable", resources.getDrawable(R.drawable.sample_icon, context.getTheme()).getClass().getSimpleName());
        result.put("raw", text(resources.openRawResource(R.raw.payload_note)).trim());
        JSONObject catalog = new JSONObject(text(context.getAssets().open("catalog/nested/catalog.json")));
        result.put("asset", catalog.getString("message"));
        result.put("assetVersion", Integer.toString(catalog.getInt("version")));
        String[] assets = context.getAssets().list("catalog");
        Arrays.sort(assets);
        result.put("assetListing", String.join(",", assets));
        Properties properties = new Properties();
        try (InputStream input = ResourceProbe.class.getResourceAsStream("/sample-app/settings.properties")) {
            if (input == null) throw new IOException("Missing application Java resource");
            properties.load(input);
        }
        result.put("javaResource", properties.getProperty("origin"));
        int count = 0;
        for (GreetingProvider provider : ServiceLoader.load(GreetingProvider.class, ResourceProbe.class.getClassLoader())) {
            result.put("dependency", provider.greeting());
            count++;
        }
        if (count != 1) throw new IllegalStateException("Expected one dependency service, found " + count);
        SampleTypes.Mapper<String> mapper = value -> value + "-lambda";
        result.put("classes", mapper.map(new SampleTypes.Box<>(SampleTypes.Mode.READY.name()).value()));
        Class<?> reflected = Class.forName("com.lelloman.paravoidandroid.sample.SampleTypes$Reflected");
        result.put("reflection", (String) reflected.getMethod("message").invoke(reflected.getConstructor().newInstance()));
        result.put("annotation", reflected.getAnnotation(SampleTypes.Label.class).value());
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new SampleTypes.SavedCounter(42), 0);
            parcel.setDataPosition(0);
            SampleTypes.SavedCounter restored = parcel.readParcelable(ResourceProbe.class.getClassLoader());
            result.put("parcelable", Integer.toString(restored.value));
        } finally {
            parcel.recycle();
        }
        return result;
    }

    private static String text(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
