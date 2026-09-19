package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import androidx.startup.Initializer;
import dagger.hilt.android.EntryPointAccessors;
import java.util.Collections;
import java.util.List;

/** Exercises a real provider creating the Hilt singleton graph before Application.onCreate. */
public final class ProbeStartupInitializer implements Initializer<ProbeService> {
    public static ProbeService service;
    public static int initializationCount;

    @Override public ProbeService create(Context context) {
        if (ProbeApplication.initializationCount != 0) throw new IllegalStateException("Startup ran after Application.onCreate");
        service = EntryPointAccessors.fromApplication(context, ProbeActivity.Services.class).service();
        if (service.context != context.getApplicationContext()) throw new IllegalStateException("Wrong startup application context");
        initializationCount++;
        return service;
    }

    @Override public List<Class<? extends Initializer<?>>> dependencies() { return Collections.emptyList(); }
}
