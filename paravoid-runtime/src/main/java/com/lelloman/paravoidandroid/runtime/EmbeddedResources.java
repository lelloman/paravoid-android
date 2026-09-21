package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK-authenticated embedded resources only. Not an external VPK loader/update selector. */
final class EmbeddedResources {
    private static final String PACK = "assets/paravoid/resources.apk";
    private static final String IDENTITY = "assets/paravoid/resources.sha256";
    private static final long LIMIT = 256L * 1024 * 1024;

    static void install(Application app) throws Exception {
        // Read the installed APK directly, never a payload-overlay AssetManager.
        try (ZipFile apk = new ZipFile(app.getApplicationInfo().sourceDir)) {
            ZipEntry identity = apk.getEntry(IDENTITY), pack = apk.getEntry(PACK);
            if (identity == null && pack == null) return; // Existing DEX-only packaging.
            if (identity == null || pack == null || identity.getSize() != 65 ||
                    pack.getSize() <= 0 || pack.getSize() > LIMIT) {
                throw new IOException("Invalid embedded resource pair");
            }
            if (android.os.Build.VERSION.SDK_INT < 30) throw new IOException("Embedded resources require API 30+");
            String hash;
            try (InputStream input = apk.getInputStream(identity)) {
                byte[] bytes = new byte[65];
                int offset = 0, count;
                while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
                hash = new String(bytes, StandardCharsets.US_ASCII);
                if (offset != 65 || input.read() != -1 || !hash.matches("[0-9a-f]{64}\\n"))
                    throw new IOException("Invalid embedded resource identity");
                hash = hash.substring(0, 64);
            }
            File root = new File(app.getNoBackupFilesDir(), "paravoid-resources");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create resource cache");
            File target = new File(root, hash + ".apk");
            try (RandomAccessFile lock = new RandomAccessFile(new File(root, "materialize.lock"), "rw");
                 FileLock ignored = lock.getChannel().lock()) {
                if (!target.exists()) {
                    File temporary = File.createTempFile("resource-", ".tmp", root);
                    try {
                        try (InputStream input = apk.getInputStream(pack); FileOutputStream output = new FileOutputStream(temporary)) {
                            byte[] buffer = new byte[8192];
                            long total = 0;
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                total += count;
                                if (total > LIMIT || total > pack.getSize()) throw new IOException("Embedded resource size exceeded");
                                output.write(buffer, 0, count);
                            }
                            if (total != pack.getSize()) throw new IOException("Truncated embedded resources");
                            output.getFD().sync();
                        }
                        if (!temporary.setReadOnly()) throw new IOException("Cannot protect embedded resources");
                        verify(temporary, hash, pack.getSize());
                        Os.rename(temporary.getPath(), target.getPath());
                    } finally {
                        if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
                    }
                }
                verify(target, hash, pack.getSize());
                Api30.attach(app, target);
            }
        }
    }

    private static void verify(File file, String hash, long size) throws Exception {
        if (file.length() != size || (Os.stat(file.getPath()).st_mode & 0222) != 0)
            throw new IOException("Invalid or writable embedded resource cache");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > size) throw new IOException("Embedded resource cache exceeds size");
                digest.update(buffer, 0, count);
            }
            if (total != size) throw new IOException("Truncated resource cache");
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!hash.contentEquals(actual)) throw new IOException("Embedded resource hash mismatch");
    }

    /** Isolate API 30 references so legacy DEX-only startup remains valid on API 28/29. */
    @android.annotation.TargetApi(30)
    private static final class Api30 {
        static void attach(Application app, File file) throws IOException {
            ResourcesLoader loader = new ResourcesLoader();
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                loader.addProvider(ResourcesProvider.loadFromApk(fd));
            }
            app.getResources().addLoaders(loader);
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPreCreated(Activity activity, Bundle state) { activity.getResources().addLoaders(loader); }
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
    }
}
