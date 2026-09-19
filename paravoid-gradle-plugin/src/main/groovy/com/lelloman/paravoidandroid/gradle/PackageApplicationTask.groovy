package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import javax.inject.Inject
import java.nio.file.Files
import java.util.zip.*

/** Splits the complete variant class graph into shell infrastructure and payload. */
@CacheableTask
abstract class PackageApplicationTask extends DefaultTask {
    static final String BASE = 'com/lelloman/paravoidandroid/runtime/ParavoidAndroidApplication'
    static final String DELEGATE = 'com/lelloman/paravoidandroid/runtime/PayloadApplication'
    @Classpath abstract ListProperty<RegularFile> getAllJars()
    @Classpath abstract ListProperty<Directory> getAllDirectories()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getMetadata()
    @Classpath abstract RegularFileProperty getD8Jar()
    @Classpath abstract RegularFileProperty getAndroidJar()
    @Input abstract Property<Integer> getMinSdk()
    @Input abstract Property<Boolean> getExperimentalHiltAdapter()
    @OutputFile abstract RegularFileProperty getShellClasses()
    @OutputFile abstract RegularFileProperty getBundleFile()
    @Inject abstract ExecOperations getExecOperations()

    PackageApplicationTask() { experimentalHiltAdapter.convention(false) }

    @TaskAction void pack() {
        Properties info = new Properties()
        metadata.get().asFile.withInputStream { info.load(it) }
        Map<String, byte[]> classes = new TreeMap<>()
        allJars.get().each { jar ->
            new ZipFile(jar.asFile).withCloseable { zip ->
                zip.entries().each { entry ->
                    if (!entry.directory && entry.name.endsWith('.class')) {
                        PackageApplicationTask.add(classes, entry.name, zip.getInputStream(entry).withCloseable { it.readAllBytes() })
                    }
                }
            }
        }
        allDirectories.get().each { directory ->
            directory.asFile.eachFileRecurse { file ->
                if (file.isFile() && file.name.endsWith('.class')) {
                    PackageApplicationTask.add(classes, directory.asFile.toPath().relativize(file.toPath()).toString().replace(File.separator, '/'), file.bytes)
                }
            }
        }
        String app = info.getProperty('application')
        if (app) {
            String parent = app.replace('.', '/')
            Set<String> visited = new HashSet<>()
            while (parent != BASE && visited.add(parent)) {
                byte[] bytes = classes.get(parent + '.class')
                if (bytes == null) break
                parent = new ClassReader(bytes).superName
            }
            if (parent != BASE) {
                throw new GradleException('The manifest Application must extend ParavoidAndroidApplication (directly or indirectly).')
            }
        }
        if (!classes.containsKey(info.getProperty('activity').replace('.', '/') + '.class')) {
            throw new GradleException('The manifest Activity is missing from the application classes.')
        }
        if (experimentalHiltAdapter.get()) HiltProbeAdapter.adapt(classes)
        PayloadActivityLoader.adapt(classes, info.getProperty('activity').replace('.', '/'))
        File shell = shellClasses.get().asFile
        shell.parentFile.mkdirs()
        File payload = new File(temporaryDir, 'payload.jar')
        new ZipOutputStream(new FileOutputStream(shell)).withCloseable { host ->
            new ZipOutputStream(new FileOutputStream(payload)).withCloseable { module ->
                classes.each { name, bytes ->
                    if (name.startsWith('com/lelloman/paravoidandroid/runtime/') || name.startsWith('com/lelloman/paravoidandroid/api/')) {
                        PackageApplicationTask.write(host, name, bytes)
                    } else {
                        ClassWriter writer = new ClassWriter(0)
                        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(BASE, DELEGATE)), 0)
                        PackageApplicationTask.write(module, name, writer.toByteArray())
                    }
                }
            }
        }
        File dexZip = new File(temporaryDir, 'dex.zip')
        Files.deleteIfExists(dexZip.toPath())
        execOperations.javaexec {
            classpath(d8Jar.get().asFile)
            mainClass.set('com.android.tools.r8.D8')
            args '--release', '--min-api', minSdk.get().toString(), '--lib', androidJar.get().asFile.absolutePath,
                '--classpath', shell.absolutePath, '--output', dexZip.absolutePath, payload.absolutePath
        }.assertNormalExitValue()
        File bundle = bundleFile.get().asFile
        bundle.parentFile.mkdirs()
        new ZipFile(dexZip).withCloseable { dex ->
            if (dex.getEntry('classes2.dex')) throw new GradleException('This experiment supports one payload DEX only.')
            new ZipOutputStream(new FileOutputStream(bundle)).withCloseable { zip ->
                PackageApplicationTask.write(zip, 'module.properties', "format=1\napi=1\nminSdk=${minSdk.get()}\nentryPoint=${info.getProperty('activity')}\n".getBytes('UTF-8'))
                PackageApplicationTask.write(zip, 'classes.dex', dex.getInputStream(dex.getEntry('classes.dex')).withCloseable { it.readAllBytes() })
            }
        }
    }

    private static void add(Map<String, byte[]> classes, String name, byte[] bytes) {
        // JPMS descriptors describe their containing JAR, not an Android runtime class.
        // Apply to both JAR and directory inputs, including multi-release descriptors.
        if (name == 'module-info.class' || name ==~ /META-INF\/versions\/[0-9]+\/module-info\.class/) return
        if (classes.putIfAbsent(name, bytes) != null) throw new GradleException("Duplicate application class: ${name}")
    }
    private static void write(ZipOutputStream output, String name, byte[] bytes) {
        ZipEntry entry = new ZipEntry(name)
        entry.time = 0
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }
}
