package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

abstract class CheckShellContractTask extends DefaultTask {
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getCandidateFile()
    @OutputFile abstract RegularFileProperty getReportFile()

    @TaskAction void check() {
        Map candidate = ShellContract.read(candidateFile.get().asFile.bytes)
        List<String> changes = baselineFile.present ? ShellContract.differences(
            ShellContract.read(baselineFile.get().asFile.bytes).descriptor, candidate.descriptor) : []
        String report = !baselineFile.present ? 'New shell generation; no accepted contract configured.' :
            changes.empty ? 'Compatible with accepted shell contract.' : 'New shell required:\n' + changes.join('\n')
        File output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(report + '\nContract ID: ' + candidate.contractId + '\n', 'UTF-8')
        if (!changes.empty) throw new GradleException(report)
    }
}
