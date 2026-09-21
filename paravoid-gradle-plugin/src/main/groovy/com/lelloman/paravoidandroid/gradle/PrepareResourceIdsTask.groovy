package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class PrepareResourceIdsTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @Input abstract Property<String> getApplicationId()
    @OutputFile abstract RegularFileProperty getStableIds()

    @TaskAction void prepare() {
        ResourceLedger ledger = ResourceLedger.read(baselineFile.get().asFile.getText('UTF-8'))
        if (ledger.applicationId != applicationId.get()) {
            throw new GradleException('Paravoid resource baseline applicationId mismatch; use the baseline for this shell variant.')
        }
        File output = stableIds.get().asFile
        output.parentFile.mkdirs()
        output.setText(ledger.toStableIds(), 'UTF-8')
    }
}
