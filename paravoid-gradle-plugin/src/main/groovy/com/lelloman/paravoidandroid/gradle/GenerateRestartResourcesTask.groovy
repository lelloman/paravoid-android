package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Restart-screen theme and strings, added only to complete shells with updates enabled so other
 * apps' resource tables and ID ledgers stay untouched. The manifest theme pins them in the shell.
 */
@CacheableTask
abstract class GenerateRestartResourcesTask extends DefaultTask {
    static final List<String> FILES = ['values/paravoid_restart.xml', 'values-it/paravoid_restart.xml', 'values-night/paravoid_restart.xml']
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction void generate() {
        File root = outputDirectory.get().asFile
        root.deleteDir()
        FILES.each { path ->
            File target = new File(root, path)
            target.parentFile.mkdirs()
            GenerateRestartResourcesTask.getResourceAsStream("restart/${path}").withCloseable { input ->
                if (input == null) throw new IllegalStateException("Missing plugin resource restart/${path}")
                target.bytes = input.bytes
            }
        }
    }
}
