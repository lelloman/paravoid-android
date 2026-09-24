package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity;
import java.io.FileNotFoundException;

/** No payload loading, network work, input replay, or fabricated application responses. */
@android.annotation.TargetApi(30)
public final class UnavailableComponents {
    private UnavailableComponents() {}
    public static final class Screen extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            startActivity(new Intent(this, CrashRecovery.instance != null ? CrashRecoveryActivity.class : ShellUpdatesActivity.class));
            finish();
        }
    }
    public static final class StartedOrBound extends Service {
        private final String declaredName;
        public StartedOrBound(String declaredName) { this.declaredName = declaredName; }
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public int onStartCommand(Intent intent, int flags, int id) {
            // stopSelf alone need not clear a foreground-start deadline while a
            // service is bound or another start is queued. Honor the actual
            // installed type and let Android enforce its permissions normally.
            try {
                android.content.pm.ServiceInfo info = getPackageManager().getServiceInfo(
                    new ComponentName(this, declaredName), 0);
                int foregroundType = info.getForegroundServiceType();
                if (foregroundType != 0) {
                    String channel = "paravoid-unavailable";
                    NotificationManager notifications = getSystemService(NotificationManager.class);
                    notifications.createNotificationChannel(new NotificationChannel(channel,
                        "App recovery", NotificationManager.IMPORTANCE_LOW));
                    Notification notification = new Notification.Builder(this, channel)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("App unavailable")
                        .setContentText("Open the app to recover.").build();
                    startForeground(0x5056, notification, foregroundType);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
                throw new IllegalStateException("Declared service unavailable");
            }
            stopSelf(id);
            return START_NOT_STICKY;
        }
    }
    public static final class Job extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) {
            // Returning true transfers completion to jobFinished, with rescheduling explicitly requested.
            new Handler(Looper.getMainLooper()).post(() -> jobFinished(parameters, true));
            return true;
        }
        @Override public boolean onStopJob(JobParameters parameters) { return true; }
    }
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { /* unavailable; intentionally not replayed */ }
    }
    public static final class Provider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        private IllegalStateException unavailable() { return new IllegalStateException("Paravoid payload unavailable; open the app to recover"); }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw unavailable(); }
        @Override public String getType(Uri uri) { throw unavailable(); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw unavailable(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw unavailable(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw unavailable(); }
        @Override public Bundle call(String method, String argument, Bundle extras) { throw unavailable(); }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            throw new FileNotFoundException("Paravoid payload unavailable");
        }
    }
}
