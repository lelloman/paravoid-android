package com.lelloman.voidandroid.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException

class VoidApplicationPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.android.application')
        def android = project.extensions.getByName('android')
        android.flavorDimensions.add('voidPackaging')
        android.productFlavors.create('normal') { dimension = 'voidPackaging' }
        android.productFlavors.create('voidAndroid') { dimension = 'voidPackaging' }
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().withFlavor('voidPackaging', 'voidAndroid')) { variant ->
            if (variant.minSdk.apiLevel < 28) throw new GradleException('VoidAndroid application packaging requires minSdk >= 28 for AppComponentFactory.')
            if (android.buildTypes.getByName(variant.buildType).minifyEnabled) throw new GradleException('VoidAndroid application packaging does not yet support shrinking.')
            if (android.compileOptions.coreLibraryDesugaringEnabled) throw new GradleException('VoidAndroid application packaging does not yet support core library desugaring.')
            String cap = variant.name.capitalize()
            def manifest = project.tasks.register("prepare${cap}VoidManifest", ApplicationManifestTask) {
                outputManifest.set(project.layout.buildDirectory.file("intermediates/void/${variant.name}/AndroidManifest.xml"))
                payloadMetadata.set(project.layout.buildDirectory.file("intermediates/void/${variant.name}/payload.properties"))
            }
            variant.artifacts.use(manifest).wiredWithFiles({ it.inputManifest }, { it.outputManifest })
                .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE)
            def pack = project.tasks.register("package${cap}VoidApplication", PackageApplicationTask) {
                metadata.set(manifest.flatMap { it.payloadMetadata })
                minSdk.set(variant.minSdk.apiLevel)
                d8Jar.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/lib/d8.jar") })
                androidJar.set(components.sdkComponents.sdkDirectory.map { it.file("platforms/android-${android.compileSdk}/android.jar") })
                shellClasses.set(project.layout.buildDirectory.file("intermediates/void/${variant.name}/shell.jar"))
                bundleFile.set(project.layout.buildDirectory.file("outputs/void/${variant.name}/module.zip"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(pack)
                .toTransform(ScopedArtifact.CLASSES.INSTANCE, { it.allJars }, { it.allDirectories }, { it.shellClasses })
            def embed = project.tasks.register("embed${cap}VoidApplication", EmbedModuleTask) {
                bundles.from(pack.flatMap { it.bundleFile })
                outputDirectory.set(project.layout.buildDirectory.dir("generated/void/${variant.name}/assets"))
            }
            variant.sources.assets.addGeneratedSourceDirectory(embed) { it.outputDirectory }
        }
    }
}
