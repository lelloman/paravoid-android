package com.lelloman.paravoidandroid.runtime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import com.lelloman.paravoidandroid.runtime.lifecycle.StartupExits;
import java.util.List;

/**
 * Reads how an unfinished trial start ended from ApplicationExitInfo (API 30+, the complete
 * profile's minimum). Crashes, native crashes, ANRs and initialization failures are evidence
 * against the payload; kills by the user or the system are not. Anything else, including a missing
 * record (power loss, reboot, a record not yet written), stays unknown and counts as before.
 */
final class AndroidStartupExits implements StartupExits {
    private final Context context;
    AndroidStartupExits(Context context) { this.context = context; }

    @Override public int currentPid() { return android.os.Process.myPid(); }
    @Override public long currentMillis() { return System.currentTimeMillis(); }

    @Override public Exit exit(int pid, long startedMillis) {
        Exit exit = Exit.UNKNOWN;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = manager == null ? null
                : manager.getHistoricalProcessExitReasons(context.getPackageName(), pid, 16);
            ApplicationExitInfo match = null;
            // The first death of that pid after the attempt started is the attempt's own.
            if (exits != null) for (ApplicationExitInfo info : exits)
                if (info.getPid() == pid && info.getTimestamp() >= startedMillis
                        && (match == null || info.getTimestamp() < match.getTimestamp())) match = info;
            if (match != null) exit = classify(match.getReason(), match.getStatus(), match.getDescription());
        } catch (RuntimeException unavailable) { /* Unknown: counts like an unexplained end. */ }
        android.util.Log.i("ParavoidAndroid", "Unfinished trial start (pid " + pid + ") ended: " + exit.label);
        return exit;
    }

    static Exit classify(int reason, int status, String description) {
        String detail = description == null || description.isEmpty() ? "" : " (" + description + ")";
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return new Exit(Kind.FAILURE, "crash");
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return new Exit(Kind.FAILURE, "native crash");
            case ApplicationExitInfo.REASON_ANR: return new Exit(Kind.FAILURE, "ANR");
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return new Exit(Kind.FAILURE, "initialization failure");
            case ApplicationExitInfo.REASON_LOW_MEMORY: return new Exit(Kind.KILLED, "killed: low memory");
            case ApplicationExitInfo.REASON_SIGNALED: return new Exit(Kind.KILLED, "killed: signal " + status);
            case ApplicationExitInfo.REASON_USER_REQUESTED: return new Exit(Kind.KILLED, "stopped by user" + detail);
            case ApplicationExitInfo.REASON_USER_STOPPED: return new Exit(Kind.KILLED, "user stopped");
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return new Exit(Kind.KILLED, "killed: permission change");
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return new Exit(Kind.KILLED, "killed: resource usage");
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return new Exit(Kind.KILLED, "killed: dependency died");
            case ApplicationExitInfo.REASON_OTHER: return new Exit(Kind.KILLED, "killed by system" + detail);
            case 14: return new Exit(Kind.KILLED, "killed: frozen"); // REASON_FREEZER, API 33
            case 15: return new Exit(Kind.KILLED, "killed: package state"); // REASON_PACKAGE_STATE_CHANGE, API 34
            case 16: return new Exit(Kind.KILLED, "killed: package updated"); // REASON_PACKAGE_UPDATED, API 34
            // A payload that exits by itself while starting is not distinguishable from a failure.
            case ApplicationExitInfo.REASON_EXIT_SELF: return new Exit(Kind.UNKNOWN, "exited (status " + status + ")");
            default: return new Exit(Kind.UNKNOWN, "unknown (reason " + reason + ")");
        }
    }
}
