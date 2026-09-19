package com.lelloman.paravoidandroid.hilt.gradle

import com.lelloman.paravoidandroid.gradle.PackageApplicationTask
import org.gradle.api.*

/** Optional build-time integration. No Hilt libraries are added to the Android shell. */
class ParavoidHiltPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.lelloman.paravoid')
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().withFlavor('paravoidPackaging', 'paravoidAndroid')) { variant ->
            if (!project.pluginManager.hasPlugin('com.google.dagger.hilt.android')) {
                throw new GradleException('paravoid-hilt requires the com.google.dagger.hilt.android plugin. Apply Hilt normally, including its compiler.')
            }
            def transformer = project.objects.newInstance(HiltPayloadTransformer)
            transformer.runtimeVersions.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map {
                HiltVersions.from(it)
            })
            project.tasks.named("package${variant.name.capitalize()}ParavoidApplication", PackageApplicationTask) {
                payloadTransformers.add(transformer)
            }
        }
    }
}
