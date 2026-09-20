package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.view.LayoutInflater;

/** Retains configuration-specific resources while resolving classes from the payload. */
public final class PayloadContext extends ContextWrapper {
    private final ClassLoader loader;
    private LayoutInflater inflater;

    private PayloadContext(Context base, ClassLoader loader) {
        super(base);
        this.loader = loader;
    }

    public static Context wrap(Context base, ClassLoader loader) {
        return new PayloadContext(base, loader);
    }

    @Override public ClassLoader getClassLoader() { return loader; }

    @Override public Object getSystemService(String name) {
        if (!LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
        if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
        return inflater;
    }

    @Override public Context createConfigurationContext(Configuration configuration) {
        return wrap(super.createConfigurationContext(configuration), loader);
    }
}
