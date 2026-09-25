package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;
import com.lelloman.paravoidandroid.contract.Protocol.LifecycleSnapshot;
import com.lelloman.paravoidandroid.delivery.DeliveryController;
import com.lelloman.paravoidandroid.delivery.UpdateControl;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
import com.lelloman.paravoidandroid.delivery.DeliveryPreferences;
import com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-local, UI-neutral update state for downstream applications. */
public final class ParavoidUpdates {
    public enum Phase { UNAVAILABLE, IDLE, CHECKING, DOWNLOADING, STAGING, WAITING_TO_RETRY, AVAILABLE, READY, CANCELLED, ERROR }

    public static final class Release {
        public final String id;
        public final long version;
        private final ExpectedArchive source;
        private Release(ExpectedArchive source) { this.source=source; id = source.releaseId; version = source.payloadVersion; }
    }

    public static final class State {
        public final Phase phase;
        public final Release current, available, pending;
        public final String errorCode;
        public final boolean automaticChecks, automaticDownloads, unmeteredOnly, promptRequired;
        public final UpdateSchedule schedule;
        public final long downloadedBytes, totalBytes, lastCheckSeconds, nextDueSeconds;
        private State(Phase phase, Release current, Release available, Release pending, String errorCode,
                boolean automaticChecks, boolean automaticDownloads, boolean unmeteredOnly) {
            this(phase,current,available,pending,errorCode,automaticChecks,automaticDownloads,unmeteredOnly,UpdateSchedule.defaults(),0,0,0,0,false);
        }
        private State(Phase phase, Release current, Release available, Release pending, String errorCode,
                boolean automaticChecks, boolean automaticDownloads, boolean unmeteredOnly,UpdateSchedule schedule,
                long bytes,long total,long lastCheck,long nextDue,boolean promptRequired) {
            this.promptRequired=promptRequired;
            this.schedule=schedule; downloadedBytes=bytes; totalBytes=total; lastCheckSeconds=lastCheck; nextDueSeconds=nextDue;
            this.phase = phase; this.current = current; this.available = available; this.pending = pending;
            this.errorCode = errorCode; this.automaticChecks = automaticChecks;
            this.automaticDownloads = automaticDownloads; this.unmeteredOnly = unmeteredOnly;
        }
        /** A verified newer release was discovered or is staged for a later cold start. */
        public boolean updateAvailable() { return available != null || pending != null; }
    }

    public interface Observer { void changed(State state); }
    public interface Subscription extends AutoCloseable { @Override void close(); }

    private static final ParavoidUpdates INSTANCE = new ParavoidUpdates();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Registration> observers = new CopyOnWriteArraySet<>();
    private volatile State state = new State(Phase.UNAVAILABLE, null, null, null, null, false, false, false);
    private volatile UpdateControl controller;

    private ParavoidUpdates() {}
    public static ParavoidUpdates get() { return INSTANCE; }
    public State current() { return state; }

    /** Call from an Activity's onStart; close the returned subscription in onStop. */
    public Subscription observe(Observer observer) {
        Registration registration = new Registration(Objects.requireNonNull(observer));
        observers.add(registration);
        main.post(() -> { if (!registration.closed) observer.changed(state); });
        return registration;
    }

    /** Discover only. A true return means the command was submitted, not that it succeeded. */
    public boolean checkNow() {
        UpdateControl value = controller;
        if (value == null) return false;
        value.checkNow(); return true;
    }
    /** Revalidate discovery, download and stage. Activation waits for a later cold start. */
    public boolean updateNow() {
        UpdateControl value=controller; if(value==null) return false; value.updateNow(); return true;
    }
    /** Update only the offer already shown to the user; changed offers require another command. */
    public boolean updateNow(Release offer) {
        Objects.requireNonNull(offer);
        UpdateControl value=controller; if(value==null) return false; value.updateNow(offer.source); return true;
    }
    public boolean dismiss(Release offer) {
        Objects.requireNonNull(offer);
        UpdateControl value=controller; if(value==null) return false; value.dismiss(offer.source); return true;
    }
    public boolean schedule(UpdateSchedule schedule) {
        Objects.requireNonNull(schedule);
        UpdateControl value=controller; if(value==null) return false; value.schedule(schedule); return true;
    }
    public boolean retry() {
        UpdateControl value = controller;
        if (value == null) return false;
        value.retry(); return true;
    }
    public boolean cancelDownload() {
        UpdateControl value = controller;
        if (value == null) return false;
        value.cancelDownload(); return true;
    }
    public boolean preferences(boolean checks, boolean downloads, boolean unmeteredOnly) {
        UpdateControl value = controller;
        if (value == null) return false;
        value.preferences(new DeliveryPreferences(checks, downloads, unmeteredOnly)); return true;
    }
    /** Restart immediately through the shell coordinator. The caller owns any confirmation. */
    public boolean restart(Activity activity) { return restart(activity,false); }
    /** With confirmation=true, show the shell confirmation before stopping app processes. */
    public boolean restart(Activity activity,boolean confirmation) {
        Objects.requireNonNull(activity);
        if(controller==null || activity.isFinishing() || activity.isDestroyed()) return false;
        activity.startActivity(new Intent(activity,RestartActivity.class).putExtra("confirmation",confirmation));
        return true;
    }
    /** Optional shell controls for confirmed restart and recovery. The app chooses its own entry point. */
    public boolean openControls(Activity activity) {
        Objects.requireNonNull(activity);
        if (controller == null) return false;
        activity.startActivity(new Intent(activity, ShellUpdatesActivity.class));
        return true;
    }

    static void install(UpdateControl value) {
        INSTANCE.controller = value;
        value.listen(INSTANCE::received);
    }

    private void received(DeliveryController.Snapshot snapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> received(snapshot)); return; }
        LifecycleSnapshot lifecycle = snapshot.lifecycle;
        DeliveryPreferences preferences = snapshot.preferences;
        state = new State(Phase.valueOf(snapshot.activity.name()),
            release(lifecycle == null ? null : lifecycle.active), release(snapshot.available),
            release(lifecycle == null ? null : lifecycle.pending), snapshot.errorCode,
            preferences.automaticChecks, preferences.automaticDownloads, preferences.unmeteredOnly, snapshot.schedule,
            snapshot.bytes,snapshot.totalBytes,snapshot.lastCheckSeconds,snapshot.nextDueSeconds,snapshot.promptRequired);
        for (Registration observer : observers) if (!observer.closed) observer.callback.changed(state);
    }
    private static Release release(ExpectedArchive value) { return value == null ? null : new Release(value); }

    private final class Registration implements Subscription {
        final Observer callback;
        volatile boolean closed;
        Registration(Observer callback) { this.callback = callback; }
        public void close() { closed = true; observers.remove(this); }
    }
}
