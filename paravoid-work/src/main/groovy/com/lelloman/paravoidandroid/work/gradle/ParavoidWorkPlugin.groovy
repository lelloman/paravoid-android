package com.lelloman.paravoidandroid.work.gradle

import com.lelloman.paravoidandroid.gradle.PackageApplicationTask
import org.gradle.api.*

/** Optional payload-only integration; no WorkManager/Hilt dependency in the shell. */
class ParavoidWorkPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.lelloman.paravoid')
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().withFlavor('paravoidPackaging', 'paravoidAndroid')) { variant ->
            def transformer = project.objects.newInstance(WorkPayloadTransformer)
            transformer.runtimeVersions.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map {
                WorkVersions.from(it)
            })
            project.tasks.named("package${variant.name.capitalize()}ParavoidApplication", PackageApplicationTask) {
                payloadTransformers.add(transformer)
            }
        }
    }
}
