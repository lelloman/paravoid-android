package com.lelloman.voidandroid.runtime;

import com.lelloman.voidandroid.api.AppEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Strict reader for the initial, code-only bundle format. Does not authenticate external files. */
final class ModuleBundle {
    final String entryPoint;
    final byte[] dex;

    private ModuleBundle(String entryPoint, byte[] dex) {
        this.entryPoint = entryPoint;
        this.dex = dex;
    }

    static ModuleBundle read(InputStream input, int deviceApi) throws IOException {
        byte[] metadata = null;
        byte[] dex = null;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!names.add(name)) throw new IOException("Duplicate module entry: " + name);
                switch (name) {
                    case "module.properties": metadata = readLimited(zip, 4096); break;
                    case "classes.dex": dex = readLimited(zip, 16 * 1024 * 1024); break;
                    default: throw new IOException("Unsupported module entry: " + name);
                }
                zip.closeEntry();
            }
        }
        if (metadata == null || dex == null) throw new IOException("Module metadata or DEX is missing.");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(metadata));
        if (!"1".equals(properties.getProperty("format"))) throw new IOException("Unsupported module format.");
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
        String entry = properties.getProperty("entryPoint", "");
        if (!entry.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            throw new IOException("Invalid module entry point.");
        }
        if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
            throw new IOException("Invalid DEX header.");
        }
        return new ModuleBundle(entry, dex);
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
