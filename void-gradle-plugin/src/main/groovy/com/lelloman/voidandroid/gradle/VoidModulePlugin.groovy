package com.lelloman.voidandroid.gradle

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.Plugin
import org.gradle.api.Project

class VoidModulePlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.android.library')
        def extension = project.extensions.create('voidModule', VoidModuleExtension)
        def android = project.extensions.getByName('android')
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().all()) { variant ->
            def capitalized = variant.name.capitalize()
            def bundle = project.tasks.register("bundle${capitalized}VoidModule", BundleModuleTask) {
                group = 'void'
                description = "Compile ${variant.name} application code into a VoidAndroid DEX bundle."
                aarFile.set(variant.artifacts.get(SingleArtifact.AAR.INSTANCE))
                entryPoint.set(extension.entryPoint)
                minSdk.set(variant.minSdk.apiLevel)
                runtimeDependencies.from(variant.runtimeConfiguration)
                d8Jar.set(components.sdkComponents.sdkDirectory.map {
                    it.file("build-tools/${android.buildToolsVersion}/lib/d8.jar")
                })
                androidJar.set(components.sdkComponents.sdkDirectory.map {
                    it.file("platforms/android-${android.compileSdk}/android.jar")
                })
                bundleFile.set(project.layout.buildDirectory.file("outputs/void/${variant.name}/module.zip"))
            }
            def elements = project.configurations.create("void${capitalized}Elements") {
                canBeConsumed = true
                canBeResolved = false
                description = "VoidAndroid bundle for ${variant.name}; not an Android runtime dependency."
                attributes.attribute(org.gradle.api.attributes.Attribute.of('com.lelloman.void.variant', String), variant.name)
            }
            project.artifacts.add(elements.name, bundle.flatMap { it.bundleFile }) {
                builtBy(bundle)
                type = 'zip'
            }
        }
    }
}
