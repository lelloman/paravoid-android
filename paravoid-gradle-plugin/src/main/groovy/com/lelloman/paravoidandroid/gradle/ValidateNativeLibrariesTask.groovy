package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/** Checks AGP's merged native output after exclusions, conservatively across ABIs. */
@CacheableTask
abstract class ValidateNativeLibrariesTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getNativeLibraries()
    @Input abstract Property<Integer> getMinSdk()
    @OutputFile abstract RegularFileProperty getValidationFile()

    @TaskAction void validate() {
        List<String> libraries = []
        nativeLibraries.asFileTree.visit { entry ->
            if (!entry.directory && entry.name.startsWith('lib') && entry.name.endsWith('.so')) {
                libraries.add(entry.relativePath.pathString)
            }
        }
        if (minSdk.get() < 29 && !libraries.empty) {
            throw new GradleException("ParavoidAndroid native libraries require minSdk >= 29; this shell variant has minSdk ${minSdk.get()}. " +
                "Merged native libraries: ${libraries.sort().join(', ')}. " +
                "Set minSdk 29 or higher for the paravoidAndroid flavor (or defaultConfig), or remove the native-bearing dependency. " +
                'This check includes all merged ABIs; ndk.abiFilters alone does not bypass it. ' +
                'Code-only shell apps may use minSdk 28; normal packaging is unaffected.')
        }
        File output = validationFile.get().asFile
        output.parentFile.mkdirs()
        output.text = "minSdk=${minSdk.get()}\n"
    }
}
