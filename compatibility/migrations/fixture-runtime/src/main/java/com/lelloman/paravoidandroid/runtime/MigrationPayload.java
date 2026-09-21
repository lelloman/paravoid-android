package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Experiment only: choose between two code bundles already inside the installed APK. */
final class MigrationPayload {
    static InputStream open(Context context) throws IOException {
        File selector = new File(context.getFilesDir(), "migration-version");
        String version = selector.exists()
            ? new String(Files.readAllBytes(selector.toPath()), StandardCharsets.UTF_8).trim() : "1";
        if (!version.equals("1") && !version.equals("2")) throw new IOException("Invalid fixture payload version");
        return context.getAssets().open("migration/v" + version + ".zip");
    }
}
