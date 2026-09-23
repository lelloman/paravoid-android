package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import static org.junit.Assert.*

class ResourceShellTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void legacySizeLimitsDoNotOverrideCompleteVpkValidation() {
        long cap = 256L * 1024 * 1024
        PackageResourceShellTask.validateArchiveSizes(false, cap, cap, cap)
        [[0L, cap, cap], [cap + 1, cap, cap], [cap, cap + 1, cap], [cap, cap, cap + 1]].each { sizes ->
            assertThrows(org.gradle.api.GradleException) {
                PackageResourceShellTask.validateArchiveSizes(false, sizes[0], sizes[1], sizes[2])
            }
        }
        PackageResourceShellTask.validateArchiveSizes(true, 1000L * 1024 * 1024, cap + 1, cap + 1)
    }

    @Test void assemblesPinnedShellWithoutInstalledFallbackAndBindsPayloadHash() {
        File original = archive('original.apk', ['AndroidManifest.xml':'manifest', 'resources.arsc':'full',
            'res/layout/panel.xml':'movable', 'res/drawable/pinned.xml':'pinned', 'assets/content':'asset',
            'classes.dex':'shell code', 'assets/paravoid/module.zip':'payload code',
            'META-INF/CERT.RSA':'signature', 'META-INF/services/example.Service':'provider', 'lib/x86_64/libtest.so':'native'])
        File pinned = archive('pinned.apk', ['AndroidManifest.xml':'manifest', 'resources.arsc':'subset', 'res/drawable/pinned.xml':'pinned'])
        File output = new File(temporary.root, 'output.apk')
        new ZipFile(original).withCloseable { source -> new ZipFile(pinned).withCloseable { resources ->
            PackageResourceShellTask.writeShell(output, source, resources, 'resource payload'.bytes)
        } }
        new ZipFile(output).withCloseable { zip ->
            assertNull(zip.getEntry('res/layout/panel.xml'))
            assertNull(zip.getEntry('assets/content'))
            assertNull(zip.getEntry('META-INF/CERT.RSA'))
            assertEquals('subset', new String(ResourceArchive.read(zip, 'resources.arsc')))
            assertEquals('pinned', new String(ResourceArchive.read(zip, 'res/drawable/pinned.xml')))
            assertEquals('shell code', new String(ResourceArchive.read(zip, 'classes.dex')))
            assertEquals('payload code', new String(ResourceArchive.read(zip, 'assets/paravoid/module.zip')))
            assertEquals('provider', new String(ResourceArchive.read(zip, 'META-INF/services/example.Service')))
            assertEquals('native', new String(ResourceArchive.read(zip, 'lib/x86_64/libtest.so')))
            assertEquals('resource payload', new String(ResourceArchive.read(zip, 'assets/paravoid/resources.apk')))
            assertEquals(java.security.MessageDigest.getInstance('SHA-256').digest('resource payload'.bytes).encodeHex().toString() + '\n',
                new String(ResourceArchive.read(zip, 'assets/paravoid/resources.sha256')))
        }
    }

    @Test void rejectsMismatchedManifestMissingTableAndUnexpectedPinnedEntries() {
        File original = archive('original.apk', ['AndroidManifest.xml':'manifest', 'assets/paravoid/module.zip':'code'])
        [['AndroidManifest.xml':'other', 'resources.arsc':'table'], ['AndroidManifest.xml':'manifest'],
         ['AndroidManifest.xml':'manifest', 'resources.arsc':'table', 'classes.dex':'unexpected']].eachWithIndex { entries, i ->
            File pinned = archive("pinned${i}.apk", entries)
            new ZipFile(original).withCloseable { source -> new ZipFile(pinned).withCloseable { resources ->
                assertThrows(org.gradle.api.GradleException) {
                    PackageResourceShellTask.writeShell(new File(temporary.root, 'out.apk'), source, resources, 'resources'.bytes)
                }
            } }
        }
    }

    private File archive(String name, Map<String, String> entries) {
        File file = new File(temporary.root, name)
        file.withOutputStream { stream -> new ZipOutputStream(stream).withCloseable { zip ->
            entries.each { path, value -> zip.putNextEntry(new ZipEntry(path)); zip.write(value.bytes); zip.closeEntry() }
        } }
        file
    }
}
