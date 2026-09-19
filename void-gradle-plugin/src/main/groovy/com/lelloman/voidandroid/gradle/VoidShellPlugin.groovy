package com.lelloman.voidandroid.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class VoidShellPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.android.application')
        def components = project.extensions.getByName('androidComponents')
        // Declarable configurations exist before the application's dependencies block.
        def modules = project.configurations.create('voidBundle') {
            canBeConsumed = false
            canBeResolved = false
        }
        components.onVariants(components.selector().all()) { variant ->
            def capitalized = variant.name.capitalize()
            def incoming = project.configurations.create("void${capitalized}BundleClasspath") {
                canBeConsumed = false
                canBeResolved = true
                transitive = false
                extendsFrom(modules)
                attributes.attribute(org.gradle.api.attributes.Attribute.of('com.lelloman.void.variant', String), variant.name)
            }
            def embed = project.tasks.register("embed${capitalized}VoidModule", EmbedModuleTask) {
                group = 'void'
                bundles.from(incoming)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/void/${variant.name}/assets"))
            }
            variant.sources.assets.addGeneratedSourceDirectory(embed) { it.outputDirectory }
        }
    }
}
