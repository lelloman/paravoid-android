package com.lelloman.paravoidandroid.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class CheckResourceBoundaryTask extends DefaultTask {
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getCandidateFile()
    @OutputFile abstract RegularFileProperty getReportFile()

    @TaskAction void checkBoundary() {
        if (!baselineFile.present) throw new GradleException('Configure paravoid.baselineDirectory with a reviewed resource-boundary.json before checking compatibility.')
        def parser = new JsonSlurper()
        def changes = PinnedResources.differences(parser.parse(baselineFile.get().asFile, 'UTF-8'), parser.parse(candidateFile.get().asFile, 'UTF-8'))
        String text = changes.empty ? 'Resource boundary unchanged; complete shell contract is not checked.\n' : changes.join('\n') + '\n'
        reportFile.get().asFile.with { parentFile.mkdirs(); setText(text, 'UTF-8') }
        if (!changes.empty) throw new GradleException('Paravoid resource boundary requires a new shell:\n' + text)
    }
}
