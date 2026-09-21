package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Resource-only archive writer. Copies bytes/compression, not APK signatures or ZIP metadata. */
final class ResourceArchive {
    static void write(File output, ZipFile source, Collection<String> paths, Map<String, byte[]> replacements = [:]) {
        List<String> names = (paths + replacements.keySet()).toSet().sort()
        names.each { name ->
            if (name.startsWith('/') || name.contains('\\') || name.split('/', -1).any { it in ['', '.', '..'] }) {
                throw new GradleException("Unsafe resource archive path: ${name}")
            }
            if (!replacements.containsKey(name) && (source.getEntry(name) == null || source.getEntry(name).directory)) {
                throw new GradleException("Missing resource archive input: ${name}")
            }
        }
        output.parentFile.mkdirs()
        output.withOutputStream { stream ->
            new ZipOutputStream(stream).withCloseable { zip ->
                names.each { name ->
                    def original = source.getEntry(name)
                    byte[] replacement = replacements[name]
                    def entry = new ZipEntry(name)
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                    entry.method = replacement != null || name == 'resources.arsc' ? ZipEntry.STORED : original.method
                    if (entry.method == ZipEntry.STORED) {
                        if (replacement != null) {
                            def crc = new CRC32()
                            crc.update(replacement)
                            entry.size = replacement.length
                            entry.crc = crc.value
                        } else {
                            entry.size = original.size
                            entry.crc = original.crc
                        }
                    }
                    zip.putNextEntry(entry)
                    if (replacement != null) zip.write(replacement)
                    else source.getInputStream(original).withCloseable { it.transferTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    static byte[] read(ZipFile zip, String path) {
        def entry = zip.getEntry(path)
        entry == null ? null : zip.getInputStream(entry).withCloseable { it.readAllBytes() }
    }
}
