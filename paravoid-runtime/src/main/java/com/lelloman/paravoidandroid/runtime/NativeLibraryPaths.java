package com.lelloman.paravoidandroid.runtime;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Uses only installed package paths; never extracts libraries into writable app storage. */
final class NativeLibraryPaths {
    private NativeLibraryPaths() {}

    static void requireSupportedApi(String path, int api) throws IOException {
        if (path != null && api < 29) {
            throw new IOException("Native libraries in Paravoid packaging require Android API 29+.");
        }
    }

    static String forApplication(ApplicationInfo info) throws IOException {
        List<String> apks = new ArrayList<>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) apks.addAll(Arrays.asList(info.splitSourceDirs));
        return build(info.nativeLibraryDir, apks,
            Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS);
    }

    static String build(String nativeDirectory, List<String> apks, String[] processAbis) throws IOException {
        // Prefer the process's supported ABI order, never mix 32-bit and 64-bit libraries.
        Set<String> available = new LinkedHashSet<>();
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    String[] parts = name.split("/");
                    if (parts.length == 3 && parts[0].equals("lib")
                            && parts[2].startsWith("lib") && parts[2].endsWith(".so")) {
                        available.add(parts[1]);
                    }
                }
            }
        }
        String selectedAbi = null;
        for (String abi : processAbis) {
            if (available.contains(abi)) { selectedAbi = abi; break; }
        }
        if (selectedAbi == null) return null;
        Set<String> paths = new LinkedHashSet<>();
        if (nativeDirectory != null && !nativeDirectory.isEmpty()) paths.add(nativeDirectory);
        for (String apk : apks) paths.add(apk + "!/lib/" + selectedAbi);
        return String.join(File.pathSeparator, paths);
    }
}
