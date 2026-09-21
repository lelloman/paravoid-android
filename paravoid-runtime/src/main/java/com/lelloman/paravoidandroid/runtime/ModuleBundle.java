package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.api.AppEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import android.os.Build;
import dalvik.system.InMemoryDexClassLoader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Strict reader for the initial, code-only bundle format. Does not authenticate external files. */
final class ModuleBundle {
    final String entryPoint;
    final byte[][] dexFiles;

    private ModuleBundle(String entryPoint, byte[][] dexFiles) {
        this.entryPoint = entryPoint;
        this.dexFiles = dexFiles;
    }

    ClassLoader createClassLoader(ClassLoader parent, String nativeLibraryPath) throws IOException {
        NativeLibraryPaths.requireSupportedApi(nativeLibraryPath, Build.VERSION.SDK_INT);
        if (dexFiles.length == 1 && nativeLibraryPath == null) {
            return new InMemoryDexClassLoader(ByteBuffer.wrap(dexFiles[0]), parent);
        }
        if (Build.VERSION.SDK_INT < 27) throw new IOException("Multiple DEX files require Android API 27+.");
        ByteBuffer[] buffers = new ByteBuffer[dexFiles.length];
        for (int i = 0; i < buffers.length; i++) buffers[i] = ByteBuffer.wrap(dexFiles[i]);
        if (Build.VERSION.SDK_INT >= 29) return new InMemoryDexClassLoader(buffers, nativeLibraryPath, parent);
        return new InMemoryDexClassLoader(buffers, parent);
    }

    static ModuleBundle read(InputStream input, int deviceApi) throws IOException {
        byte[] metadata = null;
        Map<String, byte[]> files = new HashMap<>();
        int total = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!names.add(name)) throw new IOException("Duplicate module entry: " + name);
                if (name.equals("module.properties")) {
                    metadata = readLimited(zip, 4096);
                } else if (name.matches("classes(?:[2-9]|1[0-6])?\\.dex")) {
                    byte[] dex = readLimited(zip, Math.min(32 * 1024 * 1024, 128 * 1024 * 1024 - total));
                    total += dex.length;
                    files.put(name, dex);
                } else {
                    throw new IOException("Unsupported module entry: " + name);
                }
                zip.closeEntry();
            }
        }
        if (metadata == null || files.isEmpty()) throw new IOException("Module metadata or DEX is missing.");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(metadata));
        String format = properties.getProperty("format");
        int count = 1;
        if ("2".equals(format)) {
            try { count = Integer.parseInt(properties.getProperty("dexCount", "")); }
            catch (NumberFormatException error) { throw new IOException("Invalid DEX count.", error); }
            if (count < 2 || count > 16) throw new IOException("Invalid DEX count.");
        } else if (!"1".equals(format)) throw new IOException("Unsupported module format.");
        if (files.size() != count) throw new IOException("DEX count does not match bundle.");
        if (!Integer.toString(AppEntry.API_VERSION).equals(properties.getProperty("api"))) {
            throw new IOException("Incompatible host API.");
        }
        int minSdk;
        try {
            minSdk = Integer.parseInt(properties.getProperty("minSdk", ""));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid minimum Android API.", e);
        }
        if (minSdk < 26 || minSdk > deviceApi) throw new IOException("Incompatible Android API: " + minSdk);
        if (count > 1 && minSdk < 27) throw new IOException("Multiple DEX files require minimum Android API 27+.");
        String entry = properties.getProperty("entryPoint", "");
        if (!entry.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            throw new IOException("Invalid module entry point.");
        }
        byte[][] dexFiles = new byte[count][];
        for (int i = 0; i < count; i++) {
            byte[] dex = files.get(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex");
            if (dex == null) throw new IOException("DEX sequence has a gap.");
            if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
                throw new IOException("Invalid DEX header.");
            }
            dexFiles[i] = dex;
        }
        return new ModuleBundle(entry, dexFiles);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Module entry exceeds size limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
