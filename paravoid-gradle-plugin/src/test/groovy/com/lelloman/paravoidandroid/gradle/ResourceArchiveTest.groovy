package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.*
import static org.junit.Assert.*

class ResourceArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void copiesOnlySelectedBytesDeterministicallyAndStoresResourceTable() {
        File original = temporary.newFile('original.apk')
        new ZipOutputStream(new FileOutputStream(original)).withCloseable { zip ->
            ['classes.dex', 'assets/paravoid/module.zip', 'res/raw/pinned.bin', 'resources.arsc'].each { name ->
                zip.putNextEntry(new ZipEntry(name))
                zip.write(name.bytes)
                zip.closeEntry()
            }
        }
        File a = temporary.newFile('a.apk'), b = temporary.newFile('b.apk')
        new ZipFile(original).withCloseable { zip ->
            ResourceArchive.write(a, zip, ['res/raw/pinned.bin', 'resources.arsc'], ['resources.arsc': [1, 2, 3] as byte[]])
            ResourceArchive.write(b, zip, ['resources.arsc', 'res/raw/pinned.bin'], ['resources.arsc': [1, 2, 3] as byte[]])
            assertThrows(GradleException, { ResourceArchive.write(b, zip, ['missing']) })
            assertThrows(GradleException, { ResourceArchive.write(b, zip, [], ['../escape': new byte[0]]) })
        }
        assertArrayEquals(a.bytes, b.bytes)
        new ZipFile(a).withCloseable { zip ->
            assertEquals(['res/raw/pinned.bin', 'resources.arsc'], zip.entries()*.name)
            assertEquals(ZipEntry.STORED, zip.getEntry('resources.arsc').method)
            assertArrayEquals([1, 2, 3] as byte[], ResourceArchive.read(zip, 'resources.arsc'))
            assertArrayEquals('res/raw/pinned.bin'.bytes, ResourceArchive.read(zip, 'res/raw/pinned.bin'))
        }
    }
}
