package com.lelloman.paravoidcompat.hilt;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
import javax.inject.Provider;

@AndroidEntryPoint
public final class InjectedService extends Service {
    private static ServiceToken previousToken;
    private static int creations;
    @Inject ProbeService singleton;
    @Inject ServiceToken token;
    @Inject Provider<ServiceToken> tokenProvider;
    private String run;

    @Override public void onCreate() {
        super.onCreate();
        InjectedComponentChecks.check(this, getClass(), singleton);
        if (token == null || token != tokenProvider.get() || token == previousToken) {
            throw new IllegalStateException("Service scope must be shared within, but not across, service instances");
        }
        if (getApplication() != singleton.application) throw new IllegalStateException("Service Application identity changed");
        previousToken = token;
        creations++;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (creations != intent.getIntExtra("expectedCreations", -1)) {
            throw new IllegalStateException("Expected service recreation in the same process");
        }
        run = intent.getStringExtra("probeRun");
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        InjectedComponentChecks.record(this, "injectedServiceRun", run);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
