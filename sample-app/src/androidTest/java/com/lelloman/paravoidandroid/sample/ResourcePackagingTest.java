package com.lelloman.paravoidandroid.sample;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dalvik.system.InMemoryDexClassLoader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ResourcePackagingTest {
    private static final String SAMPLE = "com.lelloman.paravoidandroid.sample.";

    @Test public void resourcesAssetsAndJavaServicesWorkFromApplicationCode() throws Exception {
        Map<String, String> data = snapshot(configured(Locale.ENGLISH, false));
        assertEquals("Hello from resources", data.get("greeting"));
        assertEquals("1 item", data.get("one"));
        assertEquals("3 items", data.get("many"));
        assertEquals("Earth,Mars", data.get("array"));
        assertEquals("7", data.get("integer"));
        assertEquals("true", data.get("boolean"));
        assertEquals("24", data.get("paddingDp"));
        assertEquals("fff5f5fa", data.get("background"));
        assertEquals("VectorDrawable", data.get("drawable"));
        assertEquals("Raw resource: caffè ☕", data.get("raw"));
        assertEquals("Caffè ☕", data.get("asset"));
        assertEquals("7", data.get("assetVersion"));
        assertEquals("nested,readme.txt", data.get("assetListing"));
        assertEquals("application-java-resource", data.get("javaResource"));
        assertEquals("Hello from the dependency", data.get("dependency"));
    }

    @Test public void localeAndNightQualifiersResolveWithoutChangingTheDevice() throws Exception {
        Map<String, String> italian = snapshot(configured(Locale.ITALIAN, true));
        assertEquals("Ciao dalle risorse", italian.get("greeting"));
        assertEquals("1 elemento", italian.get("one"));
        assertEquals("3 elementi", italian.get("many"));
        assertEquals("ff202030", italian.get("background"));
        assertEquals("24", italian.get("paddingDp"));
    }

    @Test public void classShapesReflectionAndExplicitParcelableRoundTripWork() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Map<String, String> data = snapshot(context);
        assertEquals("READY-lambda", data.get("classes"));
        assertEquals("reflection-ok", data.get("reflection"));
        assertEquals("runtime-annotation", data.get("annotation"));
        assertEquals("42", data.get("parcelable"));
        boolean dynamic = context.getPackageName().endsWith(".paravoid");
        for (String name : new String[] {SAMPLE + "ResourceProbe", SAMPLE + "SampleTypes$Mode",
                SAMPLE + "SampleTypes$Box", SAMPLE + "SampleTypes$Reflected", SAMPLE + "SampleTypes$Label",
                SAMPLE + "SampleTypes$SavedCounter$1",
                "com.lelloman.paravoidsample.library.DefaultGreetingProvider"}) {
            Class<?> type = context.getClassLoader().loadClass(name);
            assertEquals(name, dynamic, type.getClassLoader() instanceof InMemoryDexClassLoader);
            if (dynamic) assertThrows(ClassNotFoundException.class, () -> context.getClass().getClassLoader().loadClass(name));
        }
    }

    @Test public void missingResourcesRetainOrdinaryFailureBehavior() {
        Context context = ApplicationProvider.getApplicationContext();
        assertThrows(IOException.class, () -> context.getAssets().open("catalog/missing.json"));
        assertNull(context.getClassLoader().getResourceAsStream("sample-app/missing.properties"));
        assertEquals(0, context.getResources().getIdentifier("missing_resource", "raw", context.getPackageName()));
        assertThrows(Resources.NotFoundException.class, () -> context.getResources().openRawResource(0));
    }

    @Test public void installedApkBoundaryIsExplicitRatherThanMistakenForPayloadResources() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            for (String name : new String[] {"resources.arsc", "res/layout/counter_screen.xml",
                    "res/raw/payload_note.txt", "assets/catalog/nested/catalog.json",
                    "sample-app/settings.properties", "sample-library/greeting.properties",
                    "META-INF/services/com.lelloman.paravoidsample.library.GreetingProvider"}) {
                assertNotNull("Expected installed APK entry: " + name, apk.getEntry(name));
            }
            if (context.getPackageName().endsWith(".paravoid")) {
                Set<String> entries = new HashSet<>();
                try (ZipInputStream module = new ZipInputStream(apk.getInputStream(apk.getEntry("assets/paravoid/module.zip")))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = module.getNextEntry()) != null) entries.add(entry.getName());
                }
                assertEquals(new HashSet<>(java.util.Arrays.asList("module.properties", "classes.dex")), entries);
            } else {
                assertNull(apk.getEntry("assets/paravoid/module.zip"));
            }
        }
    }

    private static Context configured(Locale locale, boolean night) {
        Context context = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(config);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> snapshot(Context context) throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Class<?> probe = app.getClassLoader().loadClass(SAMPLE + "ResourceProbe");
        return (Map<String, String>) probe.getMethod("snapshot", Context.class).invoke(null, context);
    }
}
