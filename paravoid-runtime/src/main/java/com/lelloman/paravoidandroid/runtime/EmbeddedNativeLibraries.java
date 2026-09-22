package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import java.io.*;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Materializes only this APK's selected native ABI, before payload constructors. */
final class EmbeddedNativeLibraries {
    static String path(Application app) throws Exception {
        File archive = EmbeddedArchive.materialize(app, "native-libraries", ".zip");
        if (archive == null) return NativeLibraryPaths.forApplication(app.getApplicationInfo());
        try (ZipFile zip = new ZipFile(archive)) {
            Set<String> names = new HashSet<>(), abis = new HashSet<>();
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.size() > 4096) throw new IOException("Too many native entries");
            long totalSize = 0;
            for (ZipEntry entry : entries) {
                String[] parts = entry.getName().split("/", -1);
                if (entry.isDirectory() || parts.length != 3 || !parts[0].equals("lib") ||
                    !Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(parts[1]) ||
                    !parts[2].matches("lib[A-Za-z0-9_+.-]+\\.so") || parts[2].equals("libparavoid_abi.so") ||
                    !names.add(entry.getName()) || entry.getSize() <= 0 || entry.getSize() > 256L * 1024 * 1024)
                    throw new IOException("Invalid native archive entry");
                abis.add(parts[1]);
                totalSize += entry.getSize();
                if (totalSize > 2L * 1024 * 1024 * 1024) throw new IOException("Native content exceeds limits");
            }
            if (abis.isEmpty()) return null; // No installed-library fallback for a new-generation archive.
            String selected = null;
            for (String abi : Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS)
                if (abis.contains(abi)) { selected = abi; break; }
            if (selected == null) throw new IOException("No payload ABI matches the app process");
            File root = new File(archive.getParentFile(), archive.getName() + ".libs/" + selected);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create native cache");
            try (RandomAccessFile lock = new RandomAccessFile(new File(root, "materialize.lock"), "rw");
                 FileLock ignored = lock.getChannel().lock()) {
                for (ZipEntry entry : entries) {
                    String[] parts = entry.getName().split("/");
                    if (!parts[1].equals(selected)) continue;
                    byte[] expected;
                    try (InputStream input = zip.getInputStream(entry)) { expected = digest(input, entry.getSize(), null); }
                    File target = new File(root, parts[2]);
                    if (!target.exists()) {
                        File temporary = File.createTempFile("native-", ".tmp", root);
                        try {
                            try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary)) {
                                if (!Arrays.equals(expected, digest(input, entry.getSize(), output))) throw new IOException("Native hash mismatch");
                                output.getFD().sync();
                            }
                            if (!temporary.setReadOnly()) throw new IOException("Cannot protect native cache");
                            Os.rename(temporary.getPath(), target.getPath());
                        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
                    }
                    if ((Os.stat(target.getPath()).st_mode & 0222) != 0) throw new IOException("Writable native library cache");
                    try (InputStream input = new FileInputStream(target)) {
                        if (!Arrays.equals(expected, digest(input, entry.getSize(), null))) throw new IOException("Native library hash mismatch");
                    }
                }
            }
            return root.getAbsolutePath();
        }
    }

    private static byte[] digest(InputStream input, long size, OutputStream output) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > size) throw new IOException("Native content exceeds declared size");
            digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
        }
        if (total != size) throw new IOException("Truncated native content");
        return digest.digest();
    }
}
