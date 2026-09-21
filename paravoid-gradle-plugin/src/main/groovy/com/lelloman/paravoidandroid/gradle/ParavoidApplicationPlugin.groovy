package com.lelloman.paravoidandroid.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import java.security.MessageDigest

class ParavoidApplicationPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('com.android.application')
        def extension = project.extensions.create('paravoid', ParavoidApplicationExtension)
        extension.pinnedResources.convention([])
        def android = project.extensions.getByName('android')
        android.flavorDimensions.add('paravoidPackaging')
        android.productFlavors.create('normal') { dimension = 'paravoidPackaging' }
        android.productFlavors.create('paravoidAndroid') {
            dimension = 'paravoidPackaging'
            applicationIdSuffix = '.paravoid'
        }
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().withFlavor('paravoidPackaging', 'paravoidAndroid')) { variant ->
            if (variant.minSdk.apiLevel < 28) throw new GradleException('ParavoidAndroid application packaging requires minSdk >= 28 for AppComponentFactory.')
            if (android.buildTypes.getByName(variant.buildType).minifyEnabled) throw new GradleException('ParavoidAndroid application packaging does not yet support shrinking.')
            if (android.compileOptions.coreLibraryDesugaringEnabled) throw new GradleException('ParavoidAndroid application packaging does not yet support core library desugaring.')
            String cap = variant.name.capitalize()
            def baseline = extension.baselineDirectory.file("${variant.name}/resource-ledger.json")
            if (extension.baselineDirectory.present) {
                // AAPT's argument list tracks strings, not the bytes at a --stable-ids path.
                // Content-address the path so baseline-only edits invalidate resource linking.
                String variantName = variant.name
                def baselineHash = project.providers.fileContents(baseline).asText.map { text ->
                    MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString()
                }
                def stableIds = project.tasks.register("prepare${cap}ParavoidResourceIds", PrepareResourceIdsTask) {
                    baselineFile.set(baseline)
                    applicationId.set(variant.applicationId)
                    it.stableIds.set(project.layout.buildDirectory.file(baselineHash.map {
                        "intermediates/paravoid/${variantName}/resource-ids/${it}/stable-ids.txt"
                    }))
                }
                variant.androidResources.aaptAdditionalParameters.addAll(stableIds.flatMap { it.stableIds }.map {
                    ['--stable-ids', it.asFile.absolutePath]
                })
            }
            project.tasks.register("export${cap}ParavoidResourceLedger", ExportResourceLedgerTask) {
                group = 'paravoid'
                description = 'Exports a reviewable resource ID ledger; does not modify the accepted baseline.'
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK.INSTANCE))
                baselineFile.set(baseline)
                applicationId.set(variant.applicationId)
                aapt2.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/aapt2") })
                ledgerFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/baseline-candidate/resource-ledger.json"))
                stableIds.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/baseline-candidate/stable-ids.txt"))
            }
            def analysis = project.tasks.register("analyze${cap}ParavoidResources", AnalyzeResourcesTask) {
                group = 'paravoid'
                description = 'Computes manifest/explicit pinned-resource closure without changing APK contents.'
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK.INSTANCE))
                applicationId.set(variant.applicationId)
                pinnedResources.set(extension.pinnedResources)
                aapt2.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/aapt2") })
                boundaryFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/baseline-candidate/resource-boundary.json"))
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resource-boundary-report.txt"))
            }
            project.tasks.register("check${cap}ParavoidResourceBoundary", CheckResourceBoundaryTask) {
                group = 'verification'
                description = 'Checks the installed manifest and pinned-resource boundary, not the complete shell contract.'
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/resource-boundary.json"))
                candidateFile.set(analysis.flatMap { it.boundaryFile })
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resource-boundary-check.txt"))
            }
            def nativeValidation = project.tasks.register("validate${cap}ParavoidNativeLibraries", ValidateNativeLibrariesTask) {
                nativeLibraries.from(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS.INSTANCE))
                minSdk.set(variant.minSdk.apiLevel)
                validationFile.set(project.layout.buildDirectory.file("intermediates/paravoid/${variant.name}/native-validation.txt"))
            }
            def manifest = project.tasks.register("prepare${cap}ParavoidManifest", ApplicationManifestTask) {
                outputManifest.set(project.layout.buildDirectory.file("intermediates/paravoid/${variant.name}/AndroidManifest.xml"))
                payloadMetadata.set(project.layout.buildDirectory.file("intermediates/paravoid/${variant.name}/payload.properties"))
            }
            variant.artifacts.use(manifest).wiredWithFiles({ it.inputManifest }, { it.outputManifest })
                .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE)
            def pack = project.tasks.register("package${cap}ParavoidApplication", PackageApplicationTask) {
                dependsOn(nativeValidation)
                metadata.set(manifest.flatMap { it.payloadMetadata })
                minSdk.set(variant.minSdk.apiLevel)
                d8Jar.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/lib/d8.jar") })
                androidJar.set(components.sdkComponents.sdkDirectory.map { it.file("platforms/android-${android.compileSdk}/android.jar") })
                shellClasses.set(project.layout.buildDirectory.file("intermediates/paravoid/${variant.name}/shell.jar"))
                bundleFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/module.zip"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(pack)
                .toTransform(ScopedArtifact.CLASSES.INSTANCE, { it.allJars }, { it.allDirectories }, { it.shellClasses })
            def embed = project.tasks.register("embed${cap}ParavoidApplication", EmbedModuleTask) {
                bundles.from(pack.flatMap { it.bundleFile })
                outputDirectory.set(project.layout.buildDirectory.dir("generated/paravoid/${variant.name}/assets"))
            }
            variant.sources.assets.addGeneratedSourceDirectory(embed) { it.outputDirectory }
        }
    }
}
