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
        assertArrayEquals(DEX, module.dexFiles[0]);
        assertEquals(1, module.dexFiles.length);
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

    @Test public void acceptsMultipleDexInNumericOrder() throws Exception {
        ModuleBundle module = ModuleBundle.read(multiple("format=2\napi=1\nminSdk=28\nentryPoint=example.Entry\ndexCount=2\n",
            new String[] {"classes2.dex", "classes.dex"}, DEX), 36);
        assertEquals(2, module.dexFiles.length);
        assertEquals(1, module.dexFiles[0][8]);
        assertEquals(2, module.dexFiles[1][8]);
    }

    @Test public void rejectsInvalidMultidexMetadataAndSequences() throws Exception {
        String valid = "format=2\napi=1\nminSdk=28\nentryPoint=example.Entry\ndexCount=2\n";
        for (String metadata : new String[] {valid.replace("dexCount=2", "dexCount=17"),
            valid.replace("dexCount=2", "dexCount=1"), valid.replace("dexCount=2", "dexCount=invalid"),
            valid.replace("minSdk=28", "minSdk=26"), METADATA}) {
            assertThrows(IOException.class, () -> ModuleBundle.read(multiple(metadata, new String[] {"classes.dex", "classes2.dex"}, DEX), 36));
        }
        assertThrows(IOException.class, () -> ModuleBundle.read(multiple(valid, new String[] {"classes.dex", "classes3.dex"}, DEX), 36));
        assertThrows(IOException.class, () -> ModuleBundle.read(multiple(valid, new String[] {"classes.dex"}, DEX), 36));
        assertThrows(IOException.class, () -> ModuleBundle.read(multiple(valid, new String[] {"classes.dex", "classes2.dex"}, new byte[8]), 36));
    }

    @Test public void acceptsLargerUnshrunkApplicationPayload() throws Exception {
        byte[] dex = java.util.Arrays.copyOf(DEX, 30 * 1024 * 1024);
        ModuleBundle module = ModuleBundle.read(multiple(
            "format=2\napi=1\nminSdk=30\nentryPoint=example.Entry\ndexCount=3\n",
            new String[] {"classes.dex", "classes2.dex", "classes3.dex"}, dex), 36);
        assertEquals(3, module.dexFiles.length);
        for (byte[] file : module.dexFiles) assertEquals(dex.length, file.length);
    }

    @Test public void rejectsOversizedDexAndExcessTotalSize() throws Exception {
        String metadata = "format=2\napi=1\nminSdk=28\nentryPoint=example.Entry\ndexCount=5\n";
        IOException oversized = assertThrows(IOException.class, () -> ModuleBundle.read(multiple(metadata,
            new String[] {"classes.dex"}, new byte[32 * 1024 * 1024 + 1]), 36));
        assertTrue(oversized.getMessage().contains("exceeds size limit"));
        IOException aggregate = assertThrows(IOException.class, () -> ModuleBundle.read(multiple(metadata,
            new String[] {"classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex"},
            new byte[32 * 1024 * 1024]), 36));
        assertTrue(aggregate.getMessage().contains("exceeds size limit"));
        assertThrows(IOException.class, () -> ModuleBundle.read(multiple(metadata,
            new String[] {"classes17.dex"}, DEX), 36));
    }

    private static ByteArrayInputStream multiple(String metadata, String[] names, byte[] contents) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("module.properties"));
            zip.write(metadata.getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (contents == DEX) {
                    byte[] marked = java.util.Arrays.copyOf(contents, 9);
                    marked[8] = (byte) (name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length() - 4)));
                    zip.write(marked);
                } else zip.write(contents);
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(output.toByteArray());
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
