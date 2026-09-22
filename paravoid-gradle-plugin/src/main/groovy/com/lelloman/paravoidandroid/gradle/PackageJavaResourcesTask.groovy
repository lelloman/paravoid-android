package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import java.util.zip.ZipFile

/** Final standalone APK entries preserve AGP's Java-resource merge/exclude/pickFirst rules. */
@CacheableTask
abstract class PackageJavaResourcesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @OutputFile abstract RegularFileProperty getJavaResources()

    @TaskAction void pack() {
        def apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Java-resource packaging requires one standalone APK.')
        new ZipFile(apks[0]).withCloseable { apk ->
            // Everything outside the defined Android containers is a classpath resource.
            // ScopedArtifact.JAVA_RES is empty for this AGP 8.13.2 pipeline; do not
            // silently trust it or reach into private merge-task intermediate paths.
            Set<String> selected = apk.entries().findAll { entry ->
                String name = entry.name
                !entry.directory && name != 'AndroidManifest.xml' && name != 'resources.arsc' &&
                    !(name ==~ /classes(?:[2-9]|[1-9][0-9]+)?\.dex/) &&
                    !name.startsWith('res/') && !name.startsWith('assets/') && !name.startsWith('lib/')
            }.collect { it.name }.toSet()
            selected.each { name ->
                if (name == 'AndroidManifest.xml' || name == 'resources.arsc' || name.endsWith('.dex') ||
                    name.endsWith('.class') || name.startsWith('res/') || name.startsWith('assets/') || name.startsWith('lib/'))
                    throw new GradleException('Java resource collides with reserved APK content: ' + name)
            }
            // Signatures and Android packaging metadata are installed-APK infrastructure, not user resources.
            selected.removeAll { name -> name == 'META-INF/MANIFEST.MF' || name.startsWith('META-INF/com/android/') ||
                (name.startsWith('META-INF/') && name.toUpperCase(Locale.ROOT) ==~ /.*\.(SF|RSA|DSA|EC)/) }
            ResourceArchive.write(javaResources.get().asFile, apk, selected)
        }
    }
}
