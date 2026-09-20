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
    @Nested abstract ListProperty<PayloadTransformer> getPayloadTransformers()
    @OutputFile abstract RegularFileProperty getShellClasses()
    @OutputFile abstract RegularFileProperty getBundleFile()
    @Inject abstract ExecOperations getExecOperations()

    PackageApplicationTask() { payloadTransformers.convention([]) }

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
        payloadTransformers.get().each { it.transform(classes) }
        PayloadComponentLoader.adapt(classes, info.getProperty('activity').replace('.', '/'))
        info.getProperty('services', '').tokenize(';').each { service ->
            String name = service.replace('.', '/')
            // Platform-owned services have no payload class to adapt.
            if (classes.containsKey(name + '.class')) PayloadComponentLoader.adapt(classes, name)
        }
        PayloadConfigurationContext.adapt(classes, info.getProperty('activity').replace('.', '/'))
        PayloadSavedState.adapt(classes, info.getProperty('activity').replace('.', '/'))
        PayloadStateEnvelope.adapt(classes, info.getProperty('activity').replace('.', '/'))
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
            def entries = dex.entries().findAll { it.name ==~ /classes(?:[2-9]|[1-9][0-9]+)?\.dex/ }
                .sort { it.name == 'classes.dex' ? 1 : Integer.parseInt(it.name.substring(7, it.name.length() - 4)) }
            if (entries.isEmpty() || entries.size() > 16 || entries.any { it.size > 16 * 1024 * 1024 } ||
                entries.sum { it.size } > 64 * 1024 * 1024) throw new GradleException('Payload exceeds bundle DEX limits (16 files, 16 MiB each, 64 MiB total).')
            int format = entries.size() == 1 ? 1 : 2
            new ZipOutputStream(new FileOutputStream(bundle)).withCloseable { zip ->
                String metadata = "format=${format}\napi=1\nminSdk=${minSdk.get()}\nentryPoint=${info.getProperty('activity')}\n"
                if (format == 2) metadata += "dexCount=${entries.size()}\n"
                PackageApplicationTask.write(zip, 'module.properties', metadata.getBytes('UTF-8'))
                entries.each { entry -> PackageApplicationTask.write(zip, entry.name, dex.getInputStream(entry).withCloseable { it.readAllBytes() }) }
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
