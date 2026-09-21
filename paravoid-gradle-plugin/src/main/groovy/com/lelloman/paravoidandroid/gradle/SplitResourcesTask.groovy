package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** Produces unsigned resource containers, not installable shell APKs or complete VPKs. */
@CacheableTask
abstract class SplitResourcesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getAapt2()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getZipalign()
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @Input abstract Property<String> getApplicationId()
    @Input abstract ListProperty<String> getPinnedResources()
    @OutputFile abstract RegularFileProperty getShellResources()
    @OutputFile abstract RegularFileProperty getPayloadResources()
    @OutputFile abstract RegularFileProperty getReportFile()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction void split() {
        List<File> apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Paravoid resource splitting requires one standalone APK.')
        File inputProto = new File(temporaryDir, 'input-proto.apk')
        convert(apks[0], inputProto, 'proto')
        Map pinned, complete
        Set<String> shellFiles, payloadFiles, assets
        File shellAligned = new File(temporaryDir, 'shell-aligned.apk')
        File payloadAligned = new File(temporaryDir, 'payload-aligned.apk')
        new ZipFile(apks[0]).withCloseable { original ->
            new ZipFile(inputProto).withCloseable { proto ->
                byte[] tableBytes = ResourceArchive.read(proto, 'resources.pb')
                def table = tableBytes == null ? Resources.ResourceTable.defaultInstance : Resources.ResourceTable.parseFrom(tableBytes)
                def manifest = Resources.XmlNode.parseFrom(ResourceArchive.read(proto, 'AndroidManifest.xml'))
                Set<String> allNames = []
                table.packageList.each { pkg -> pkg.typeList.each { type -> type.entryList.each { allNames.add("${type.name}/${it.name}".toString()) } } }
                def analyze = { roots -> PinnedResources.analyze(applicationId.get(), table, manifest, roots,
                    { ResourceArchive.read(original, it) }, { ResourceArchive.read(proto, it) }) }
                pinned = analyze(pinnedResources.get())
                // Validate movable references/files too; a complete payload must be internally coherent.
                complete = analyze(allNames)
                if (baselineFile.present) {
                    def changes = PinnedResources.differences(new JsonSlurper().parse(baselineFile.get().asFile, 'UTF-8'), pinned)
                    if (!changes.empty) throw new GradleException('Resource split requires a new shell:\n' + changes.join('\n'))
                }
                shellFiles = pinned.pinned.collectMany { it.files.keySet() }.toSet()
                payloadFiles = complete.pinned.collectMany { it.files.keySet() }.toSet()
                Set<String> originalPaths = original.entries().findAll { !it.directory }.collect { it.name }.toSet()
                Set<String> unclassified = originalPaths.findAll { it.startsWith('res/') }.toSet() - payloadFiles
                if (!unclassified.empty) throw new GradleException("Unclassified res/ files in APK: ${unclassified.sort()}")
                assets = originalPaths.findAll { it.startsWith('assets/') && it != 'assets/paravoid/module.zip' }.toSet()
                if (assets.any { it.startsWith('assets/paravoid/') }) throw new GradleException('Unknown reserved Paravoid assets cannot be moved into resource payloads.')
                def subset = ResourceTableSubset.retain(table, pinned.pinned*.name as Set)
                File shellProto = new File(temporaryDir, 'shell-proto.apk')
                ResourceArchive.write(shellProto, proto, shellFiles + 'AndroidManifest.xml', ['resources.pb': subset.toByteArray()])
                File shellConverted = new File(temporaryDir, 'shell-converted.apk')
                convert(shellProto, shellConverted, 'binary')
                byte[] shellTable
                new ZipFile(shellConverted).withCloseable { shellTable = ResourceArchive.read(it, 'resources.arsc') }
                if (shellTable == null) throw new GradleException('AAPT2 did not produce the shell resource table')
                File shellUnaligned = new File(temporaryDir, 'shell-unaligned.apk')
                // Restore original compiled XML/images exactly; only resources.arsc is regenerated.
                ResourceArchive.write(shellUnaligned, original, shellFiles + 'AndroidManifest.xml', ['resources.arsc': shellTable])
                File payloadUnaligned = new File(temporaryDir, 'payload-unaligned.apk')
                Set<String> payloadPaths = payloadFiles + assets + 'AndroidManifest.xml'
                if (originalPaths.contains('resources.arsc')) payloadPaths.add('resources.arsc')
                ResourceArchive.write(payloadUnaligned, original, payloadPaths)
                align(shellUnaligned, shellAligned)
                align(payloadUnaligned, payloadAligned)
            }
        }
        verify(shellAligned, pinned, 'shell')
        verify(payloadAligned, complete, 'payload')
        // Publish outputs only after both binary round trips and alignment checks pass.
        [[shellAligned, shellResources.get().asFile], [payloadAligned, payloadResources.get().asFile]].each { pair ->
            pair[1].parentFile.mkdirs()
            Files.copy(pair[0].toPath(), pair[1].toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        def report = [version: 1, applicationId: applicationId.get(), installedApkModified: false,
                      shellResources: pinned.pinned*.name, payloadResources: complete.pinned*.name,
                      shellFiles: shellFiles.sort(), payloadFiles: payloadFiles.sort(), payloadAssets: assets.sort(),
                      resourceBoundaryChecked: baselineFile.present,
                      limitation: 'Unsigned resource containers only; runtime attachment and complete shell-contract validation are not implemented.']
        reportFile.get().asFile.with { parentFile.mkdirs(); setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n', 'UTF-8') }
    }

    void convert(File input, File output, String format) {
        execOperations.exec {
            commandLine(aapt2.get().asFile.absolutePath, 'convert', '--output-format', format, '-o', output.absolutePath, input.absolutePath)
        }.assertNormalExitValue()
    }

    void align(File input, File output) {
        execOperations.exec { commandLine(zipalign.get().asFile.absolutePath, '-f', '4', input.absolutePath, output.absolutePath) }.assertNormalExitValue()
        execOperations.exec { commandLine(zipalign.get().asFile.absolutePath, '-c', '4', output.absolutePath) }.assertNormalExitValue()
    }

    void verify(File binary, Map expected, String label) {
        File converted = new File(temporaryDir, "verify-${label}.apk")
        convert(binary, converted, 'proto')
        new ZipFile(binary).withCloseable { original ->
            new ZipFile(converted).withCloseable { proto ->
                byte[] bytes = ResourceArchive.read(proto, 'resources.pb')
                def actual = PinnedResources.analyze(applicationId.get(), bytes == null ? Resources.ResourceTable.defaultInstance : Resources.ResourceTable.parseFrom(bytes),
                    Resources.XmlNode.parseFrom(ResourceArchive.read(proto, 'AndroidManifest.xml')), expected.pinned*.name,
                    { ResourceArchive.read(original, it) }, { ResourceArchive.read(proto, it) })
                List<String> changes = PinnedResources.differences(expected, actual)
                if (!changes.empty || !actual.movable.empty) {
                    throw new GradleException("${label} resource round trip changed IDs/values/files or retained unexpected entries: ${changes}; extras=${actual.movable}")
                }
            }
        }
    }
}
