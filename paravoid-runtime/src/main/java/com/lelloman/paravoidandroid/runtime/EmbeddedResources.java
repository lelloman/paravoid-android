package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;

/** Loads only resources authenticated by the installed APK. */
final class EmbeddedResources {
    static void install(Application app) throws Exception {
        File file = EmbeddedArchive.materialize(app, "resources", ".apk");
        if (file != null) Api30.attach(app, file);
    }
    /** Only for files returned by a verified, process-leased complete generation. */
    static void installVerified(Application app, File file) throws IOException { Api30.attach(app, file); }

    @android.annotation.TargetApi(30)
    private static final class Api30 {
        static void attach(Application app, File file) throws IOException {
            ResourcesLoader loader = new ResourcesLoader();
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                loader.addProvider(ResourcesProvider.loadFromApk(fd));
            }
            app.getResources().addLoaders(loader);
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPreCreated(Activity activity, Bundle state) { activity.getResources().addLoaders(loader); }
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
    }
}
