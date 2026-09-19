package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@CacheableTask
abstract class BundleModuleTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getAarFile()
    @Classpath abstract RegularFileProperty getD8Jar()
    @Classpath abstract RegularFileProperty getAndroidJar()
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getRuntimeDependencies()
    @Input abstract Property<String> getEntryPoint()
    @Input abstract Property<Integer> getMinSdk()
    @OutputFile abstract RegularFileProperty getBundleFile()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction
    void bundle() {
        String entry = entryPoint.get()
        if (!(entry ==~ /[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+/)) {
            throw new GradleException('paravoidModule.entryPoint must be a fully qualified class name.')
        }
        if (minSdk.get() < 26) {
            throw new GradleException('ParavoidAndroid requires minSdk >= 26 for in-memory DEX loading.')
        }
        if (!runtimeDependencies.empty) {
            throw new GradleException('The first ParavoidAndroid module format does not bundle runtime dependencies. ' +
                'Use compileOnly dependencies supplied by both hosts. Found: ' + runtimeDependencies.files)
        }
        File jar = new File(temporaryDir, 'classes.jar')
        new ZipFile(aarFile.get().asFile).withCloseable { aar ->
            aar.entries().each { item ->
                if (!item.directory && (item.name.startsWith('res/') || item.name.startsWith('assets/') ||
                    item.name.startsWith('jni/') || item.name.startsWith('libs/'))) {
                    throw new GradleException("Unsupported module content: ${item.name}. This prototype supports code-only modules.")
                }
            }
            def classes = aar.getEntry('classes.jar')
            if (classes == null) throw new GradleException('Module AAR has no classes.jar.')
            aar.getInputStream(classes).withCloseable { input ->
                jar.withOutputStream { output -> input.transferTo(output) }
            }
        }
        new ZipFile(jar).withCloseable { classes ->
            if (classes.getEntry(entry.replace('.', '/') + '.class') == null) {
                throw new GradleException("Entry point ${entry} is not present in the module.")
            }
            if (classes.entries().any { it.name.startsWith('com/lelloman/paravoidandroid/api/') }) {
                throw new GradleException('The host API must not be packaged in the module; use compileOnly.')
            }
        }
        File dexZip = new File(temporaryDir, 'dex.zip')
        Files.deleteIfExists(dexZip.toPath())
        execOperations.javaexec {
            classpath(d8Jar.get().asFile)
            mainClass.set('com.android.tools.r8.D8')
            args '--release', '--min-api', minSdk.get().toString(), '--lib', androidJar.get().asFile.absolutePath,
                '--output', dexZip.absolutePath, jar.absolutePath
        }.assertNormalExitValue()
        byte[] dex
        new ZipFile(dexZip).withCloseable { result ->
            if (result.getEntry('classes2.dex') != null) {
                throw new GradleException('The initial ParavoidAndroid format supports a single DEX file only.')
            }
            dex = result.getInputStream(result.getEntry('classes.dex')).withCloseable { it.readAllBytes() }
        }
        File output = bundleFile.get().asFile
        output.parentFile.mkdirs()
        output.withOutputStream { stream ->
            new ZipOutputStream(stream).withCloseable { zip ->
                BundleModuleTask.writeEntry(zip, 'module.properties',
                    "format=1\napi=1\nminSdk=${minSdk.get()}\nentryPoint=${entry}\n".getBytes('UTF-8'))
                BundleModuleTask.writeEntry(zip, 'classes.dex', dex)
            }
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) {
        def entry = new ZipEntry(name)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}
