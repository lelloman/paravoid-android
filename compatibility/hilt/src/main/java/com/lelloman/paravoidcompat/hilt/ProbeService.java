package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import android.app.Application;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ProbeService {
    public final Context context;
    public final Application application;
    @Inject public ProbeService(@ApplicationContext Context context, Application application) {
        this.context = context;
        this.application = application;
    }
}
