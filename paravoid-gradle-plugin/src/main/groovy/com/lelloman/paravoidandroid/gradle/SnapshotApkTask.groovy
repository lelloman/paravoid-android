package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import javax.inject.Inject
import com.android.build.api.artifact.ArtifactTransformationRequest
import org.gradle.api.provider.Property

/** Captures AGP's pre-shell APK as an independent producer, avoiding final-artifact dependency cycles. */
abstract class SnapshotApkTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getInputDirectory()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @OutputDirectory abstract DirectoryProperty getSnapshotDirectory()
    @Internal abstract Property<ArtifactTransformationRequest> getTransformation()
    @Inject abstract FileSystemOperations getFileOperations()
    @TaskAction void snapshot() {
        fileOperations.sync { from(inputDirectory); into(outputDirectory) }
        transformation.get().submit(this, { artifact -> new File(outputDirectory.get().asFile, new File(artifact.outputFile).name) })
        fileOperations.sync { from(outputDirectory); into(snapshotDirectory) }
    }
}
