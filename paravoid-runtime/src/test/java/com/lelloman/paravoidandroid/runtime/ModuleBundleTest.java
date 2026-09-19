package com.lelloman.paravoidandroid.runtime;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ModuleBundleTest {
    private static final String METADATA = "format=1\napi=1\nminSdk=26\nentryPoint=example.Entry\n";
    private static final byte[] DEX = new byte[] {'d', 'e', 'x', '\n', '0', '3', '8', 0};

    @Test public void acceptsCompatibleBundle() throws Exception {
        ModuleBundle module = ModuleBundle.read(bundle(METADATA, DEX), 26);
        assertEquals("example.Entry", module.entryPoint);
        assertArrayEquals(DEX, module.dex);
    }

    @Test public void rejectsIncompatibleMetadataBeforeClassLoading() throws Exception {
        for (String metadata : new String[] {
            METADATA.replace("format=1", "format=2"),
            METADATA.replace("api=1", "api=2"),
            METADATA.replace("minSdk=26", "minSdk=37"),
            METADATA.replace("minSdk=26", "minSdk=invalid"),
            METADATA.replace("entryPoint=example.Entry", "entryPoint=../Entry")
        }) {
            assertThrows(IOException.class, () -> ModuleBundle.read(bundle(metadata, DEX), 36));
        }
    }

    @Test public void rejectsMissingOrInvalidDex() throws Exception {
        assertThrows(IOException.class, () -> ModuleBundle.read(bundle(METADATA, null), 36));
        assertThrows(IOException.class, () -> ModuleBundle.read(bundle(METADATA, new byte[20]), 36));
    }

    @Test public void rejectsOversizedMetadata() throws Exception {
        assertThrows(IOException.class, () -> ModuleBundle.read(bundle(METADATA + "x".repeat(4096), DEX), 36));
    }

    @Test public void rejectsUnexpectedArchiveEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../classes.dex"));
            zip.write(DEX);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> ModuleBundle.read(new ByteArrayInputStream(output.toByteArray()), 36));
    }

    private static ByteArrayInputStream bundle(String metadata, byte[] dex) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("module.properties"));
            zip.write(metadata.getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
            if (dex != null) {
                zip.putNextEntry(new ZipEntry("classes.dex"));
                zip.write(dex);
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(output.toByteArray());
    }
}
