package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.*;
import android.view.View;
import android.view.ViewTreeObserver;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.runtime.lifecycle.RuntimeLifecycle;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.zip.*;

/** Complete-profile Android bootstrap. The recovery process never acquires a payload lease. */
final class CompleteRuntime {
    static final String RECOVERY_SUFFIX = ":paravoid_recovery";
    private final Application app;
    private final AndroidLifecycleEnvironment environment;
    final ShellPolicy policy;
    final RuntimeLifecycle lifecycle;
    final boolean shellOnly;
    private final boolean mainProcess;
    private final DeliveryController controller;
    private GenerationLease lease;
    private boolean uiHealthy;

    CompleteRuntime(Application app) throws Exception {
        this.app = app;
        environment = new AndroidLifecycleEnvironment(app);
        ApkInstalledStateSource source = new ApkInstalledStateSource(environment, new SignedMetadataVerifier());
        policy = source.read().policy;
        if (!policy.applicationId.equals(app.getPackageName())) throw new IOException("Installed policy identity mismatch");
        shellOnly = Application.getProcessName().equals(app.getPackageName() + RECOVERY_SUFFIX);
        mainProcess = environment.mainProcess();
        RequestScope scope = environment.scope(policy);
        lifecycle = new RuntimeLifecycle(new File(app.getNoBackupFilesDir(), "paravoid-v1"), policy, scope,
            new CompleteVpkVerifier(), environment, mainProcess, source);
        lifecycle.openOrInitialize();
        // Delivery's OS transfer lock serializes shared partials; credential and
        // authentication suppression must be visible in every app process.
        DeliveryClient client = new DeliveryClient(policy, new SignedMetadataVerifier(), lifecycle, environment,
            new File(app.getNoBackupFilesDir(), "paravoid-delivery/shared-v1"));
        Handler main = new Handler(Looper.getMainLooper());
        controller = new DeliveryController(client, lifecycle, scope, environment,
            new File(app.getNoBackupFilesDir(), "paravoid-update-preferences"),
            () -> {
                ConnectivityManager network = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                return network == null || network.isActiveNetworkMetered();
            }, Executors.newSingleThreadScheduledExecutor(), main::post);
        if (policy.updatesEnabled) controller.refreshInstalledApk(environment.currentBaseApk());
        if (shellOnly) ShellUpdatesActivity.installController(controller);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityResumed(Activity activity) {
                if (policy.updatesEnabled && (mainProcess || shellOnly)) controller.foreground();
                if (!mainProcess || shellOnly || lease == null || uiHealthy || activity instanceof LauncherActivity) return;
                View view = activity.getWindow().getDecorView();
                ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
                    boolean posted;
                    public void onDraw() {
                        if (posted) return; posted = true;
                        view.post(() -> {
                            if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnDrawListener(this);
                            if (activity.isFinishing() || activity.isDestroyed() || uiHealthy) return;
                            try { lease.firstFrameRendered(); uiHealthy = true; }
                            catch (ContractException error) { safeLog(error.code); }
                        });
                    }
                };
                view.getViewTreeObserver().addOnDrawListener(listener);
                view.invalidate();
            }
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}
        });
    }
    ClassLoader load(ClassLoader parent) throws Exception {
        if (shellOnly) throw new IllegalStateException("Recovery process cannot load payload code");
        LifecycleSnapshot state = lifecycle.snapshot();
        if (policy.bootstrap == Bootstrap.EMBEDDED && state.active == null && state.pending == null) stageEmbedded();
        try { lease = lifecycle.acquireForProcess(); }
        catch (ContractException error) {
            // A repair APK can carry a higher release under the same contract too.
            // Admission still enforces lineage floors and immutable identities;
            // staging an intact quarantined release never clears its quarantine.
            if (policy.bootstrap != Bootstrap.EMBEDDED ||
                    (error.code != ContractException.Code.INCOMPATIBLE &&
                     error.code != ContractException.Code.UNAVAILABLE &&
                     error.code != ContractException.Code.INTEGRITY)) throw error;
            stageEmbedded(); lease = lifecycle.acquireForProcess();
        }
        return CompleteGenerationLoader.load(app, lease, parent);
    }
    private void stageEmbedded() throws Exception {
        Path temporary = Files.createTempFile(app.getNoBackupFilesDir().toPath(), "paravoid-embedded-", ".vpk");
        try {
            try (ZipFile apk = new ZipFile(environment.currentBaseApk())) {
                ZipEntry entry = apk.getEntry("assets/paravoid/payload.vpk");
                if (entry == null || entry.getSize() < 1 || entry.getSize() > Protocol.MAX_ARCHIVE_BYTES)
                    throw new IOException("Embedded VPK missing or too large");
                try (InputStream in = apk.getInputStream(entry); OutputStream out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n; if (total > entry.getSize()) throw new IOException("Embedded VPK size mismatch");
                        out.write(buffer, 0, n);
                    }
                    if (total != entry.getSize()) throw new IOException("Truncated embedded VPK");
                }
            }
            lifecycle.stageEmbedded(temporary.toFile());
        } finally { Files.deleteIfExists(temporary); }
    }
    void applicationCreated() throws ContractException { if (lease != null) lease.applicationCreated(); }
    void failed() {
        if (lease != null) try { lease.startupFailed(ContractException.Code.UNAVAILABLE); }
        catch (ContractException error) { safeLog(error.code); }
    }
    private static void safeLog(ContractException.Code code) { android.util.Log.e("ParavoidAndroid", "Lifecycle: " + code.name()); }
}
