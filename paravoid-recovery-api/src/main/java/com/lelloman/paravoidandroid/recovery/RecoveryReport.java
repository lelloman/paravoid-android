package com.lelloman.paravoidandroid.recovery;

import java.io.File;
import java.util.Locale;

/**
 * What the recovery screen knows about the app and its latest crash. Crash details can contain
 * app data from exception messages: ask the user before sending them anywhere.
 */
public final class RecoveryReport {
    public final String applicationId;
    /** Installed shell APK version, "name (code)". */
    public final String shellVersion;
    /** The user's current language, for action labels and messages. */
    public final Locale locale;
    /** True when the screen shows an unacknowledged crash, false when opened from update controls. */
    public final boolean crashed;
    /** Text of the latest crash record (also shown by "Crash details"); empty when there is none. */
    public final String crashDetails;
    /** Private, non-backed-up directory owned by the action provider, e.g. to queue reports. */
    public final File privateDirectory;

    public RecoveryReport(String applicationId, String shellVersion, Locale locale, boolean crashed,
            String crashDetails, File privateDirectory) {
        this.applicationId = applicationId; this.shellVersion = shellVersion; this.locale = locale;
        this.crashed = crashed; this.crashDetails = crashDetails == null ? "" : crashDetails;
        this.privateDirectory = privateDirectory;
    }
}
