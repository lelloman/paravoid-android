package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.os.Build;
import com.lelloman.paravoidandroid.api.AppEntry;
import dalvik.system.InMemoryDexClassLoader;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Loads only the module shipped inside the installed, signed shell APK. */
public final class BundledModuleLoader {
    private BundledModuleLoader() {}

    public static AppEntry load(Context context) throws IOException, ReflectiveOperationException {
        ModuleBundle bundle = ModuleBundle.read(context.getAssets().open("paravoid/module.zip"), Build.VERSION.SDK_INT);
        ClassLoader parent = AppEntry.class.getClassLoader();
        InMemoryDexClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(bundle.dex), parent);
        Class<?> entry = loader.loadClass(bundle.entryPoint);
        if (entry.getClassLoader() != loader) {
            throw new IOException("Module entry point was bundled into the shell classpath.");
        }
        if (!AppEntry.class.isAssignableFrom(entry)) {
            throw new IOException("Module entry point does not implement the host AppEntry contract.");
        }
        return entry.asSubclass(AppEntry.class).getConstructor().newInstance();
    }
}
