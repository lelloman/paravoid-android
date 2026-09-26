package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Read fresh across payload, recovery and update processes; disabled on a new installation. */
final class BackgroundPushPreference {
    private BackgroundPushPreference() {}
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(),"paravoid-background-push-v1"));
    }
    static boolean read(Context context) {
        try {
            byte[] bytes=file(context).readFully();
            return bytes.length==1 && bytes[0]=='1';
        } catch(IOException unavailable) { return false; }
    }
    static boolean write(Context context,boolean enabled) {
        AtomicFile file=file(context);
        FileOutputStream stream=null;
        try {
            stream=file.startWrite(); stream.write(enabled ? '1' : '0'); file.finishWrite(stream);
            return true;
        } catch(IOException unavailable) {
            if(stream!=null) file.failWrite(stream);
            return false;
        }
    }
}
