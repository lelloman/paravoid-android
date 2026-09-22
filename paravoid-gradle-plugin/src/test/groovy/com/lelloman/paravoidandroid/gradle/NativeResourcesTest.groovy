package com.lelloman.paravoidandroid.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.zip.*
import static org.junit.Assert.*

class NativeResourcesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    private void pack(String name, int elfClass, int machine) {
        def root = temporary.newFolder()
        def apkDir = new File(root, 'apk'); apkDir.mkdirs()
        byte[] bytes = new byte[20]
        bytes[0] = 127; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70
        bytes[4] = (byte) elfClass; bytes[5] = 1; bytes[16] = 3; bytes[18] = (byte) machine
        new ZipOutputStream(new FileOutputStream(new File(apkDir, 'app.apk'))).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        def task = project.tasks.create('nativePack', PackageNativeResourcesTask)
        task.apkDirectory.set(apkDir)
        File output = new File(root, 'native.zip'); task.nativeArchive.set(output)
        task.pack()
        new ZipFile(output).withCloseable { assertArrayEquals(bytes, ResourceArchive.read(it, name)) }
    }

    @Test void preservesCppRuntimeAndBothBitnesses() {
        pack('lib/x86_64/libc++_shared.so', 2, 62)
        pack('lib/x86/libexample.so', 1, 3)
        pack('lib/arm64-v8a/libexample.so', 2, 183)
    }

    @Test void rejectsWrongElfAbiAndReservedMarker() {
        assertThrows(GradleException) { pack('lib/x86/libexample.so', 2, 62) }
        assertThrows(GradleException) { pack('lib/x86_64/libparavoid_abi.so', 2, 62) }
        assertThrows(GradleException) { pack('lib/unsupported/libexample.so', 2, 62) }
    }

    @Test void packagedMarkersMatchTheirAbi() {
        ['arm64-v8a':[2,183], 'armeabi-v7a':[1,40], 'x86':[1,3], 'x86_64':[2,62]].each { abi, expected ->
            byte[] bytes = getClass().getResourceAsStream('/com/lelloman/paravoid/abi/' + abi + '.base64').withCloseable {
                Base64.mimeDecoder.decode(it.readAllBytes())
            }
            assertEquals(127, bytes[0]); assertEquals(69, bytes[1]); assertEquals(76, bytes[2]); assertEquals(70, bytes[3])
            assertEquals(expected[0], bytes[4] as Integer)
            assertEquals(expected[1], (bytes[18] & 255) | ((bytes[19] & 255) << 8))
        }
    }
}
