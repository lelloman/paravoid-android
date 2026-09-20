package com.lelloman.paravoidandroid.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NativeLibraryPathsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void prefersProcessAbiOrderAndExtractedDirectory() throws Exception {
        String base = apk("base.apk", "lib/arm64-v8a/liba.so", "lib/x86_64/liba.so");
        assertEquals("/installed/lib" + File.pathSeparator + base + "!/lib/x86_64",
            NativeLibraryPaths.build("/installed/lib", Collections.singletonList(base), new String[] {"x86_64", "arm64-v8a"}));
    }

    @Test public void includesBaseAndSplitPathsForSplitOnlyNativeCode() throws Exception {
        String base = apk("base.apk", "classes.dex");
        String split = apk("split.apk", "lib/arm64-v8a/liba.so");
        assertEquals(base + "!/lib/arm64-v8a" + File.pathSeparator + split + "!/lib/arm64-v8a",
            NativeLibraryPaths.build(null, Arrays.asList(base, split), new String[] {"arm64-v8a"}));
    }

    @Test public void ignoresUnrelatedEntriesAndAllowsCodeOnlyPackages() throws Exception {
        String base = apk("base.apk", "assets/lib/x86_64/liba.so", "lib/x86_64/notes.txt",
            "lib/x86_64/deeper/liba.so", "lib/x86_64/not-a-library.so");
        assertNull(NativeLibraryPaths.build("/installed/lib", Collections.singletonList(base), new String[] {"x86_64"}));
    }

    @Test public void neverFallsBackToAnIncompatibleProcessAbi() throws Exception {
        String base = apk("base.apk", "lib/arm64-v8a/liba.so");
        assertNull(NativeLibraryPaths.build(null, Collections.singletonList(base), new String[] {"armeabi-v7a"}));
    }

    @Test public void deduplicatesArchivePathsWithoutAnExtractedDirectory() throws Exception {
        String base = apk("base.apk", "lib/x86_64/liba.so");
        assertEquals(base + "!/lib/x86_64",
            NativeLibraryPaths.build("", Arrays.asList(base, base), new String[] {"x86_64"}));
    }

    @Test public void failsClosedForUnreadableInstalledArchives() {
        assertThrows(IOException.class, () -> NativeLibraryPaths.build(null,
            Collections.singletonList(new File(temporary.getRoot(), "missing.apk").getPath()), new String[] {"x86_64"}));
    }

    @Test public void requiresApi29OnlyWhenNativeLibrariesArePresent() throws Exception {
        for (int api : new int[] {26, 27, 28}) {
            NativeLibraryPaths.requireSupportedApi(null, api);
            IOException failure = assertThrows(IOException.class,
                () -> NativeLibraryPaths.requireSupportedApi("/installed/lib", api));
            assertTrue(failure.getMessage().contains("API 29+"));
        }
        NativeLibraryPaths.requireSupportedApi("/installed/lib", 29);
        NativeLibraryPaths.requireSupportedApi("/installed/lib", 36);
    }

    private String apk(String name, String... entries) throws Exception {
        File file = temporary.newFile(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry)); zip.write(1); zip.closeEntry();
            }
        }
        return file.getAbsolutePath();
    }
}
