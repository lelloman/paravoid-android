package com.lelloman.voidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@CacheableTask
abstract class EmbedModuleTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getBundles()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void embed() {
        if (bundles.files.size() != 1) {
            throw new GradleException('Each shell variant requires exactly one void module bundle.')
        }
        File target = outputDirectory.file('void/module.zip').get().asFile
        target.parentFile.mkdirs()
        Files.copy(bundles.singleFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
