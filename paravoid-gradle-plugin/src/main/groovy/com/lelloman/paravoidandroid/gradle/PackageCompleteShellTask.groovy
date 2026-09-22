package com.lelloman.paravoidandroid.gradle

import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.nio.file.*
import com.android.build.api.artifact.ArtifactTransformationRequest
import org.gradle.api.provider.Property

/** Final AGP APK transform. All analysis consumes the independent pre-shell snapshot. */
abstract class PackageCompleteShellTask extends PackageResourceShellTask {
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @Internal abstract Property<ArtifactTransformationRequest> getTransformation()
    @Override @TaskAction void assemble() {
        if (!completePolicy.present) throw new org.gradle.api.GradleException('Complete shell policy is required.')
        super.assemble()
        File output = outputDirectory.get().asFile; output.mkdirs()
        transformation.get().submit(this, { artifact ->
            File target = new File(output, new File(artifact.outputFile).name)
            Files.copy(shellApk.get().asFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        })
    }
}
