package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.zip.ZipFile

/** Observes the final linked table; does not yet compute pinned-resource ownership. */
@CacheableTask
abstract class ExportResourceLedgerTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getAapt2()
    @Input abstract Property<String> getApplicationId()
    @OutputFile abstract RegularFileProperty getLedgerFile()
    @OutputFile abstract RegularFileProperty getStableIds()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction void export() {
        List<File> apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Paravoid resource ledger export requires exactly one standalone APK; split APKs are not supported.')
        def baseline = baselineFile.present ? ResourceLedger.read(baselineFile.get().asFile.getText('UTF-8')) : null
        boolean hasTable = new ZipFile(apks[0]).withCloseable { it.getEntry('resources.arsc') != null }
        ResourceLedger ledger
        if (hasTable) {
            def dump = new ByteArrayOutputStream()
            execOperations.exec {
                commandLine(aapt2.get().asFile.absolutePath, 'dump', 'resources', '--no-values', apks[0].absolutePath)
                standardOutput = dump
            }.assertNormalExitValue()
            ledger = ResourceLedger.fromDump(applicationId.get(), dump.toString('UTF-8'), baseline)
        } else {
            ledger = ResourceLedger.reconcile(applicationId.get(), [], baseline)
        }
        [ (ledgerFile.get().asFile): ledger.toJson(), (stableIds.get().asFile): ledger.toStableIds() ].each { file, text ->
            file.parentFile.mkdirs()
            file.setText(text, 'UTF-8')
        }
    }
}
