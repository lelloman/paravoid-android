package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import java.util.zip.ZipFile

@CacheableTask
abstract class PackageNativeResourcesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @OutputFile abstract RegularFileProperty getNativeArchive()

    @TaskAction void pack() {
        def apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Native payload packaging requires one standalone APK.')
        new ZipFile(apks[0]).withCloseable { apk ->
            Set<String> names = apk.entries().findAll { !it.directory && it.name.startsWith('lib/') }.collect { it.name }.toSet()
            if (names.size() > 4096 || names.sum(0L) { apk.getEntry(it).size } > 2L * 1024 * 1024 * 1024)
                throw new GradleException('Native payload exceeds entry/content limits.')
            names.each { name ->
                String[] parts = name.split('/')
                Map<String, List<Integer>> machines = ['armeabi-v7a':[1,40], 'arm64-v8a':[2,183], 'x86':[1,3], 'x86_64':[2,62]]
                if (parts.length != 3 || !machines.containsKey(parts[1]) || !(parts[2] ==~ /lib[A-Za-z0-9_+.-]+\.so/) || parts[2] == 'libparavoid_abi.so')
                    throw new GradleException('Unsupported or reserved native path: ' + name)
                if (apk.getEntry(name).size > 256L * 1024 * 1024) throw new GradleException('Native library exceeds 256 MiB: ' + name)
                byte[] header = new byte[20]
                apk.getInputStream(apk.getEntry(name)).withCloseable { new DataInputStream(it).readFully(header) }
                if (header[0] != 127 || header[1] != 69 || header[2] != 76 || header[3] != 70 || header[5] != 1 || header[16] != 3 || header[17] != 0 ||
                    header[4] != machines[parts[1]][0] || ((header[18] & 255) | ((header[19] & 255) << 8)) != machines[parts[1]][1])
                    throw new GradleException('Native ELF header does not match ABI: ' + name)
            }
            ResourceArchive.write(nativeArchive.get().asFile, apk, names)
        }
    }
}
