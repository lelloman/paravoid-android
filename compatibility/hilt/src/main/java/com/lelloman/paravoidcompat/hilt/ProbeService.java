package com.lelloman.paravoidcompat.hilt;

import android.content.Context;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ProbeService {
    public final Context context;
    @Inject public ProbeService(@ApplicationContext Context context) { this.context = context; }
}
