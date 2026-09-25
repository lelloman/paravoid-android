package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Shell-owned setting read from both the app and recovery processes. */
public final class AutoRestartPreference {
    private static final String FILE = "paravoid-auto-restart-v1";
    private AutoRestartPreference() {}

    public static boolean read(Context context, boolean fallback) {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
        try {
            byte[] bytes = file.readFully();
            return bytes.length == 1 && (bytes[0] == '0' || bytes[0] == '1')
                ? bytes[0] == '1' : fallback;
        } catch (IOException unavailable) {
            return fallback;
        }
    }

    public static boolean write(Context context, boolean enabled) {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(enabled ? '1' : '0');
            file.finishWrite(stream);
            return true;
        } catch (IOException unavailable) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }
}
