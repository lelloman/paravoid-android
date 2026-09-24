package com.lelloman.paravoidcompat.minification;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.widget.TextView;
import dalvik.system.InMemoryDexClassLoader;
import java.util.ServiceLoader;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView result = new TextView(this);
        result.setTextSize(20);
        result.setPadding(32, 48, 32, 32);
        result.setTag("minification-result");
        setContentView(result);
        try {
            check();
            result.setText("PASS: reflection, service discovery, Parcelable, provider, receiver class, and payload loader");
        } catch (Exception | LinkageError failure) {
            result.setText("FAIL: " + failure);
        }
    }

    private void check() throws Exception {
        boolean shell = getPackageName().endsWith(".paravoid");
        require((getClass().getClassLoader() instanceof InMemoryDexClassLoader) == shell, "payload loader");
        require(getSharedPreferences("probe", MODE_PRIVATE).getBoolean("applicationStarted", false), "Application");

        Class<?> reflected = Class.forName("com.lelloman.paravoidcompat.minification.ReflectiveProbe", true, getClassLoader());
        require("reflected".equals(reflected.getMethod("message").invoke(reflected.getConstructor().newInstance())), "reflection");

        int providers = 0;
        for (Greeting greeting : ServiceLoader.load(Greeting.class, getClassLoader())) {
            require("service".equals(greeting.value()), "ServiceLoader result");
            providers++;
        }
        require(providers == 1, "ServiceLoader provider count");

        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new ParcelProbe(42), 0);
            parcel.setDataPosition(0);
            ParcelProbe restored = parcel.readParcelable(getClassLoader());
            require(restored != null && restored.value == 42, "Parcelable");
        } finally {
            parcel.recycle();
        }

        ContentResolver resolver = getContentResolver();
        Bundle response = resolver.call(Uri.parse("content://" + getPackageName() + ".probe"), "ping", null, null);
        require(response != null && "pong".equals(response.getString("result")), "provider");
        require(getClassLoader().loadClass("com.lelloman.paravoidcompat.minification.ProbeReceiver") != null, "receiver class");
        require(RenameProbe.answer() == 7, "obfuscatable class");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException(label);
    }
}
