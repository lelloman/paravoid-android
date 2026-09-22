package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
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
final class EmbeddedArchive {
    private static final long LIMIT = 256L * 1024 * 1024;

    static File materialize(Application app, String name, String extension) throws Exception {
        // Read the installed APK directly, never a payload-overlay AssetManager.
        try (ZipFile apk = new ZipFile(app.getApplicationInfo().sourceDir)) {
            ZipEntry identity = apk.getEntry("assets/paravoid/" + name + ".sha256");
            ZipEntry pack = apk.getEntry("assets/paravoid/" + name + extension);
            if (identity == null && pack == null) return null; // Existing DEX-only packaging.
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
            File root = new File(app.getNoBackupFilesDir(), "paravoid-" + name);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create resource cache");
            File target = new File(root, hash + extension);
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
                return target;
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

}
