package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources
import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.zip.ZipFile

@CacheableTask
abstract class AnalyzeResourcesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getAapt2()
    @Input abstract Property<String> getApplicationId()
    @Input abstract ListProperty<String> getPinnedResources()
    @OutputFile abstract RegularFileProperty getBoundaryFile()
    @OutputFile abstract RegularFileProperty getReportFile()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction void analyze() {
        List<File> apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Paravoid resource analysis requires exactly one standalone APK.')
        File converted = new File(temporaryDir, 'resources-proto.apk')
        execOperations.exec {
            commandLine(aapt2.get().asFile.absolutePath, 'convert', '--output-format', 'proto', '-o', converted.absolutePath, apks[0].absolutePath)
        }.assertNormalExitValue()
        Map report
        new ZipFile(apks[0]).withCloseable { original ->
            new ZipFile(converted).withCloseable { proto ->
                byte[] table = AnalyzeResourcesTask.read(proto, 'resources.pb')
                byte[] manifest = AnalyzeResourcesTask.read(proto, 'AndroidManifest.xml')
                if (manifest == null) throw new GradleException('Converted APK has no manifest')
                report = PinnedResources.analyze(applicationId.get(), table == null ? Resources.ResourceTable.defaultInstance : Resources.ResourceTable.parseFrom(table),
                    Resources.XmlNode.parseFrom(manifest), pinnedResources.get(), { AnalyzeResourcesTask.read(original, it) }, { AnalyzeResourcesTask.read(proto, it) })
            }
        }
        boundaryFile.get().asFile.with { parentFile.mkdirs(); setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n', 'UTF-8') }
        String text = "Resource boundary only: not a complete shell compatibility approval.\n" +
            "Application: ${report.applicationId}\nPinned: ${report.pinned.size()}; movable: ${report.movable.size()}\n"
        report.pinned.each { resource ->
            text += "PIN ${resource.name} ${resource.id} (${resource.configurations} configurations)\n" +
                "  ${resource.chain.join(' -> ')}${resource.roots ? ' [' + resource.roots.join(', ') + ']' : ''}\n"
        }
        report.movable.each { text += "MOVABLE ${it}\n" }
        reportFile.get().asFile.with { parentFile.mkdirs(); setText(text, 'UTF-8') }
    }

    private static byte[] read(ZipFile zip, String path) {
        def entry = zip.getEntry(path)
        entry == null ? null : zip.getInputStream(entry).withCloseable { it.readAllBytes() }
    }
}
