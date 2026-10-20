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
    private final UpdateControl controller;
    private GenerationLease lease;
    private boolean uiHealthy;
    private boolean applicationReady;
    private int visibleActivities;
    private Activity resumedActivity;
    private ExpectedArchive promptedOffer;
    private ExpectedArchive handledRestart;

    void suspendUpdates() { ((RemoteUpdates)controller).suspend(); }
    void resumeUpdates() { ((RemoteUpdates)controller).resume(); }

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
            new CompleteVpkVerifier(), environment, mainProcess, source, new AndroidStartupExits(app));
        lifecycle.openOrInitialize();
        try { LegacyDeliveryCleanup.reclaim(app.getNoBackupFilesDir(), app.getPackageName()); }
        catch (IOException ignored) { /* Old partials are disposable; retry cleanup on next start. */ }
        controller = new RemoteUpdates(app);
        if(policy.updatesEnabled && (mainProcess || shellOnly)) controller.listen(snapshot-> {
            if(mainProcess && resumedActivity!=null && !(resumedActivity instanceof LauncherActivity) && snapshot.lifecycle!=null) {
                ExpectedArchive pending=snapshot.lifecycle.pending;
                String configured=policy.updates.getOrDefault("restartBehavior","manual");
                String behavior=RestartPolicy.effectiveBehavior(configured,
                    AutoRestartPreference.read(app,configured.equals("automatic")));
                String previous=app.getSharedPreferences(RestartActivity.PREFERENCES,0).getString("handled","");
                if(RestartPolicy.shouldOffer(behavior,pending,handledRestart,previous)) {
                    handledRestart=pending;
                    resumedActivity.startActivity(new android.content.Intent(resumedActivity,RestartActivity.class)
                        .putExtra("offer",UpdateWire.offer(pending)).putExtra("confirmation",behavior.equals("prompt"))
                        .putExtra("policy",true));
                    return;
                }
            }
            if(resumedActivity!=null && snapshot.promptRequired && !snapshot.available.equals(promptedOffer)) {
                promptedOffer=snapshot.available;
                resumedActivity.startActivity(new android.content.Intent(resumedActivity,UpdatePromptActivity.class)
                    .putExtra("offer",UpdateWire.offer(snapshot.available)));
            }
        });
        if (policy.updatesEnabled && (mainProcess || shellOnly)) ParavoidUpdates.install(controller);
        if (shellOnly) {
            ShellUpdatesActivity.installController(controller);
            if (CrashRecovery.instance != null) {
                CrashRecovery.instance.coordinator = new RecoveryCoordinator(controller, CrashRecovery.instance.records);
            }
            ShellUpdatesActivity.installRestartAction(result -> ShellRestart.restart(app, result));
            ShellUpdatesActivity.installAutoRestartDefault("automatic".equals(policy.updates.get("restartBehavior")));
        }
        if (mainProcess || shellOnly) ShellControlShortcut.install(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) {}
            public void onActivityStarted(Activity activity) { if(policy.updatesEnabled && (mainProcess || shellOnly)) if(++visibleActivities==1) ((RemoteUpdates)controller).visible(true); }
            public void onActivityResumed(Activity activity) {
                resumedActivity=activity;
                if(policy.updatesEnabled && (mainProcess || shellOnly) && !ShellRestart.inProgress()) PushForegroundService.startConfigured(activity);
                if(!ShellRestart.inProgress()) resumeUpdates();
                if (policy.updatesEnabled && (mainProcess || shellOnly)) controller.foreground();
                if (mainProcess && !shellOnly) controller.refreshSnapshot();
                if (!mainProcess || shellOnly || lease == null || uiHealthy || activity instanceof LauncherActivity) return;
                View view = activity.getWindow().getDecorView();
                ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
                    boolean posted;
                    public void onDraw() {
                        if (posted) return; posted = true;
                        view.post(() -> {
                            if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnDrawListener(this);
                            if (activity.isFinishing() || activity.isDestroyed() || uiHealthy) return;
                            try {
                                lease.firstFrameRendered(); uiHealthy = true;
                                if (CrashRecovery.instance != null) CrashRecovery.instance.healthy();
                            }
                            catch (ContractException error) { safeLog(error.code); }
                        });
                    }
                };
                view.getViewTreeObserver().addOnDrawListener(listener);
                view.invalidate();
            }
            public void onActivityPaused(Activity activity) { if(resumedActivity==activity) resumedActivity=null; }
            public void onActivityStopped(Activity activity) { if(policy.updatesEnabled && (mainProcess || shellOnly)) if(--visibleActivities==0) ((RemoteUpdates)controller).visible(false); }
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}
        });
    }
    ClassLoader load(ClassLoader parent) throws Exception {
        if (shellOnly) throw new IllegalStateException("Recovery process cannot load payload code");
        LifecycleSnapshot state = lifecycle.snapshot();
        if (CrashRecovery.instance != null) {
            CrashRecovery.instance.identity(policy.shellContractId, state.active);
            if (CrashRecovery.instance.blocked()) throw new CrashRecovery.Required();
        }
        if (policy.bootstrap == Bootstrap.EMBEDDED && state.active == null && state.pending == null) {
            // The update process may be staging right now (it starts on MY_PACKAGE_REPLACED and on
            // boot). Let it finish, and stage the embedded payload only if nothing usable appeared.
            lifecycle.awaitIdleWriters(STARTUP_WRITER_WAIT_MILLIS);
            LifecycleSnapshot settled = lifecycle.snapshot();
            if (settled.active == null && settled.pending == null) stageEmbedded();
        }
        try { lease = lifecycle.acquireForProcess(); }
        catch (ContractException error) {
            // A repair APK can carry a higher release under the same contract too.
            // Admission still enforces lineage floors and immutable identities;
            // staging an intact quarantined release never clears its quarantine.
            if (!embeddedRepairable(error)) throw error;
            // After a contract-changing APK update, the update process can hold the space while it
            // stages a compatible release. Wait for it and retry before staging our own copy.
            GenerationLease settled = null;
            if (lifecycle.awaitIdleWriters(STARTUP_WRITER_WAIT_MILLIS)) {
                try { settled = lifecycle.acquireForProcess(); }
                catch (ContractException again) { if (!embeddedRepairable(again)) throw again; }
            }
            if (settled == null) settled = embeddedRepair();
            lease = settled;
        }
        installStartupObserver();
        if (CrashRecovery.instance != null) {
            CrashRecovery.instance.identity(policy.shellContractId, lease.release().identity);
            CrashRecovery.instance.install();
        }
        return CompleteGenerationLoader.load(app, lease, parent);
    }
    /**
     * Bounded wait for another update writer during startup. It runs on the main thread before any
     * window exists, so it must stay far below Android's ANR limits (about 5 seconds for input, 10 for
     * a missing focused window). Long writers are kept away instead: update downloads and staging
     * wait until this process has bootstrapped the installed contract (see RuntimeLifecycle).
     */
    static final long STARTUP_WRITER_WAIT_MILLIS = 2_000;
    private boolean embeddedRepairable(ContractException error) {
        return policy.bootstrap == Bootstrap.EMBEDDED &&
            (error.code == ContractException.Code.INCOMPATIBLE ||
             error.code == ContractException.Code.UNAVAILABLE ||
             error.code == ContractException.Code.INTEGRITY);
    }
    /**
     * Stages the embedded payload and loads it, unless the selected generation is quarantined with
     * nothing newer pending. Then the embedded payload helps only as a forward repair (a higher
     * version, e.g. from a repair APK) or a byte repair of the same release. An older one is below
     * the replay floor and must never run, and its refusal (REPLAY) is not why the app cannot start:
     * fail with the quarantine's own reason, which becomes the recovery record.
     */
    private GenerationLease embeddedRepair() throws Exception {
        LifecycleSnapshot state = lifecycle.snapshot();
        if (state.availability != Availability.RECOVERY || state.pending != null) {
            stageEmbedded(); return lifecycle.acquireForProcess();
        }
        String report = lifecycle.quarantineReport();
        try { stageEmbedded(); return lifecycle.acquireForProcess(); }
        catch (ContractException | IOException refused) {
            ContractException.Code code = refused instanceof ContractException ? ((ContractException) refused).code : null;
            PayloadQuarantined failure = new PayloadQuarantined(report, code);
            android.util.Log.w("ParavoidAndroid", failure.getMessage());
            throw failure;
        }
    }
    /** No runnable payload: the selected one is quarantined and nothing admissible can replace it. */
    static final class PayloadQuarantined extends IOException {
        PayloadQuarantined(String report, ContractException.Code embedded) { super(message(report, embedded)); }
        static String message(String report, ContractException.Code embedded) {
            String reason = report != null ? report : "The selected payload is paused.";
            String replacement = embedded == ContractException.Code.REPLAY
                ? "The app's built-in payload is older than one this device already accepted, so it is not used."
                : embedded == ContractException.Code.UNAVAILABLE ? "The app's built-in payload does not replace it."
                : "The app's built-in payload cannot replace it (" + (embedded == null ? "IO" : embedded.name()) + ").";
            return reason + " " + replacement + " Retry the app or install an update.";
        }
    }
    private void stageEmbedded() throws Exception {
        try (ZipFile apk = new ZipFile(environment.currentBaseApk())) {
            ZipEntry entry = apk.getEntry("assets/paravoid/payload.vpk");
            if (entry == null || entry.getSize() < 1 || entry.getSize() > Protocol.MAX_ARCHIVE_BYTES)
                throw new IOException("Embedded VPK missing or too large");
            try (RuntimeLifecycle.EmbeddedReservation reservation = lifecycle.reserveEmbedded(entry.getSize(), STARTUP_WRITER_WAIT_MILLIS)) {
                try (InputStream in = apk.getInputStream(entry); OutputStream out = new FileOutputStream(reservation.sourceFile())) {
                    byte[] buffer = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n; if (total > entry.getSize()) throw new IOException("Embedded VPK size mismatch");
                        out.write(buffer, 0, n);
                    }
                    if (total != entry.getSize()) throw new IOException("Truncated embedded VPK");
                }
                reservation.stage();
            }
        }
    }
    void applicationCreated() throws ContractException {
        if (lease != null) lease.applicationCreated();
        applicationReady = true;
        installStartupObserver(); // Preserve/wrap a handler installed by user Application.onCreate.
    }
    private void installStartupObserver() {
        Thread mainThread = Looper.getMainLooper().getThread();
        if (Thread.currentThread() != mainThread) throw new IllegalStateException("Payload bootstrap must run on the main thread");
        if (!(mainThread.getUncaughtExceptionHandler() instanceof StartupFailureObserver))
            mainThread.setUncaughtExceptionHandler(new StartupFailureObserver(mainThread,
                mainThread.getUncaughtExceptionHandler(), this::startupObservationEligible, failure -> failed()));
    }
    private boolean startupObservationEligible() {
        if (shellOnly || lease == null || uiHealthy) return false;
        if (!applicationReady || mainProcess) return true;
        // A background process has no first-frame milestone. Observe component
        // creation only while the selected generation is still on its initial trial.
        try { return lifecycle.snapshot().availability == Availability.TRIAL; }
        catch (ContractException unavailable) { return false; }
    }
    void failed() {
        if (lease != null) try { lease.startupFailed(ContractException.Code.UNAVAILABLE); }
        catch (ContractException error) { safeLog(error.code); }
    }
    private static void safeLog(ContractException.Code code) { android.util.Log.e("ParavoidAndroid", "Lifecycle: " + code.name()); }
}
