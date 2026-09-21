package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Fixture-only early hook. Hashes are pinned at build time; no external updater. */
public final class SplitResources {
    private SplitResources() {}
    public static void install(Context context) {
        Application app = context instanceof Application ? (Application) context : (Application) context.getApplicationContext();
        try {
            File root = new File(app.getFilesDir(), "split");
            String selected = new String(Files.readAllBytes(new File(root, "selected").toPath()), StandardCharsets.UTF_8).trim();
            if (!selected.equals("A") && !selected.equals("B") && !selected.equals("none"))
                throw new IllegalArgumentException("Unknown resource selection");
            if (selected.equals("none")) return; // Installed-table negative control.
            ResourcesLoader loader = new ResourcesLoader();
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(root, selected + ".apk"), ParcelFileDescriptor.MODE_READ_ONLY)) {
                if ((Os.fstat(fd.getFileDescriptor()).st_mode & 0222) != 0)
                    throw new IllegalArgumentException("Resource pack must be read-only");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
                    byte[] buffer = new byte[8192];
                    int count, total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 1024 * 1024) throw new IllegalArgumentException("Resource pack too large");
                        digest.update(buffer, 0, count);
                    }
                }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
                if (!hash.toString().equals(selected.equals("A") ? SplitTrusted.A : SplitTrusted.B))
                    throw new IllegalArgumentException("Resource pack hash mismatch");
                Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_SET);
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
        } catch (Exception failure) {
            app.getSharedPreferences("split-probe", 0).edit().putInt("failurePid", android.os.Process.myPid())
                .putString("failure", failure.toString()).commit();
            throw new IllegalStateException("Resource fixture initialization failed", failure);
        }
    }
}
