package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the stores listed in {@code busyReportStores} whether the app is busy, so they hold
 * installing a shell APK update meanwhile. The protocol is the store's update-consent provider:
 * {@code content://<store>.update-consent}, method {@code report_state_v1}, argument
 * {@code idle} or {@code busy}, and a Binder token the store watches to forget the report when
 * this process dies.
 */
final class StoreBusyReports {
    static final String METADATA = "paravoid.busyReportStores";
    private static final String METHOD = "report_state_v1";
    private final Application app;
    private final List<String> authorities;
    // One token for the life of the process: the stores watch it to learn when the process dies.
    private final Binder token = new Binder();
    // Single thread, so reports reach each store in the order the app made them.
    private final ExecutorService reporter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "paravoid-busy-reports");
        thread.setDaemon(true);
        return thread;
    });

    private StoreBusyReports(Application app, List<String> authorities) { this.app = app; this.authorities = authorities; }

    static void install(Application app) {
        List<String> authorities = authorities(app);
        if (authorities.isEmpty()) return;
        StoreBusyReports reports = new StoreBusyReports(app, authorities);
        ParavoidUpdates.listenBusy(busy -> reports.reporter.execute(() -> reports.report(busy)));
    }

    /** The update-consent provider authorities of the configured store packages. */
    static List<String> parse(String configured) {
        List<String> authorities = new ArrayList<>();
        if (configured == null) return authorities;
        for (String name : configured.split(";")) {
            if (!name.isEmpty()) authorities.add(name + ".update-consent");
        }
        return authorities;
    }

    private static List<String> authorities(Application app) {
        try {
            Bundle metadata = app.getPackageManager()
                .getApplicationInfo(app.getPackageName(), PackageManager.GET_META_DATA).metaData;
            return parse(metadata == null ? null : metadata.getString(METADATA));
        } catch (PackageManager.NameNotFoundException unavailable) {
            return new ArrayList<>();
        }
    }

    private void report(boolean busy) {
        Bundle extras = new Bundle();
        extras.putBinder("token", token);
        for (String authority : authorities) {
            try {
                app.getContentResolver().call(Uri.parse("content://" + authority), METHOD, busy ? "busy" : "idle", extras);
            } catch (RuntimeException unavailable) {
                // The store is not installed, or does not take reports. It installs as it always did.
            }
        }
    }
}
