package com.lelloman.paravoidandroid.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/** Public build evidence only. No signing-key files, grants, or credential-bearing configuration inputs. */
@CacheableTask
abstract class GeneratePackagingReportTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getPolicyFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBoundaryFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getLedgerFile()
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @Input abstract Property<String> getPackaging()
    @OutputFile abstract RegularFileProperty getExportedContract()
    @OutputFile abstract RegularFileProperty getExportedLedger()
    @OutputFile abstract RegularFileProperty getJsonReport()
    @OutputFile abstract RegularFileProperty getTextReport()

    @TaskAction void generate() {
        Map policy = ShellContract.read(policyFile.get().asFile.bytes)
        Map descriptor = policy.descriptor
        if (descriptor.profile != 'complete-apk-v1') throw new GradleException('Complete packaging report requires a complete APK policy.')
        Map boundary = new JsonSlurper().parse(boundaryFile.get().asFile)
        ResourceLedger ledger = ResourceLedger.read(ledgerFile.get().asFile.getText('UTF-8'))
        if (boundary.applicationId != descriptor.installed.applicationId || ledger.applicationId != boundary.applicationId)
            throw new GradleException('Packaging report inputs have different application IDs.')
        List<String> changes = baselineFile.present ? ShellContract.differences(
            ShellContract.read(baselineFile.get().asFile.bytes).descriptor, descriptor) : []
        String compatibility = !baselineFile.present ? 'new-shell' : changes.empty ? 'compatible' : 'new-shell-required'
        Map report = [version: 1, profile: descriptor.profile, applicationId: boundary.applicationId,
            shellContractId: policy.contractId, packaging: packaging.get(),
            distribution: descriptor.distribution.subMap(['bootstrap', 'enabled', 'channel', 'authentication', 'debugHttpAllowed']),
            compatibility: [status: compatibility, changes: changes],
            trustKeyIds: ['releaseKeys', 'headKeys', 'grantKeys'].collectEntries { role -> [(role): descriptor.trustPolicy[role].keySet().sort()] },
            ownership: [shell: [runtimeClassCount: descriptor.installed.runtimeClasses.size(),
                    nativeAbiMarkers: descriptor.installed.nativeAbis.keySet().sort(),
                    pinnedResources: boundary.pinned.collect { it.subMap(['name', 'id', 'sha256', 'configurations', 'roots', 'chain']) }],
                payload: [code: 'All non-shell application/library classes', resources: boundary.movable,
                    assets: 'Ordinary application/library assets', javaResources: 'Merged non-class Java resources',
                    nativeLibraries: 'Application/library native libraries; shell retains only ABI markers']],
            ledger: [entries: ledger.entries.size(), tombstones: ledger.entries.count { it.removed }],
            unsupportedFeatures: ['AGP minification/resource shrinking', 'Core-library desugaring', 'Shell AAB/split APK distribution',
                'Activity aliases or multiple launcher destinations', 'Isolated/direct-boot payload components'],
            validation: 'Build-time packaging evidence; not installed-device or release-readiness acceptance']
        String text = "Paravoid complete packaging report\nApplication: ${report.applicationId}\nContract: ${policy.contractId}\n" +
            "Packaging: ${packaging.get()}; bootstrap: ${descriptor.distribution.bootstrap}\nCompatibility: ${compatibility}\n"
        changes.each { text += it + '\n' }
        report.trustKeyIds.each { role, ids -> text += "${role}: ${ids.join(', ')}\n" }
        report.ownership.shell.pinnedResources.each { pin ->
            text += "PIN ${pin.name} ${pin.id} (${pin.configurations} configurations)\n  ${pin.chain.join(' -> ')} [${pin.roots.join(', ')}]\n"
        }
        boundary.movable.each { text += "MOVABLE ${it}\n" }
        text += "Payload owns code, ordinary assets, merged Java resources and native libraries.\n" +
            "Unsupported: ${report.unsupportedFeatures.join('; ')}\n${report.validation}\n"
        write(exportedContract, policyFile.get().asFile.bytes)
        write(exportedLedger, ledgerFile.get().asFile.bytes)
        write(jsonReport, (JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n').getBytes('UTF-8'))
        write(textReport, text.getBytes('UTF-8'))
    }
    private static void write(RegularFileProperty output, byte[] bytes) {
        File file = output.get().asFile; file.parentFile.mkdirs(); file.bytes = bytes
    }
}
