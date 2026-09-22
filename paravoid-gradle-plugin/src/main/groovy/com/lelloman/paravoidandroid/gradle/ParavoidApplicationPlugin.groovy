package com.lelloman.paravoidandroid.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.builder.model.Version
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
            String packaging = extension.packaging.get()
            if (!(packaging in ['dexOnly', 'complete'])) throw new GradleException('paravoid.packaging must be dexOnly or complete.')
            boolean complete = packaging == 'complete'
            if (complete && variant.minSdk.apiLevel < 30) throw new GradleException('Complete packaging requires minSdk >= 30 on the paravoidAndroid flavor.')
            def apkInputs = variant.artifacts.get(SingleArtifact.APK.INSTANCE)
            if (complete) {
                def snapshot = project.tasks.register("snapshot${cap}ParavoidInput", SnapshotApkTask) {
                    snapshotDirectory.set(project.layout.buildDirectory.dir("intermediates/paravoid/${variant.name}/input-apk"))
                }
                def captureRequest = variant.artifacts.use(snapshot).wiredWithDirectories({ it.inputDirectory }, { it.outputDirectory })
                    .toTransformMany(SingleArtifact.APK.INSTANCE)
                snapshot.configure { transformation.set(captureRequest) }
                apkInputs = snapshot.flatMap { it.snapshotDirectory }
                project.tasks.matching { it.name == "bundle${cap}" }.configureEach {
                    doFirst { throw new GradleException('Complete shell distribution requires a standalone APK; normal AABs are unchanged.') }
                }
            }
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
            def ledger = project.tasks.register("export${cap}ParavoidResourceLedger", ExportResourceLedgerTask) {
                group = 'paravoid'
                description = 'Exports a reviewable resource ID ledger; does not modify the accepted baseline.'
                apkDirectory.set(apkInputs)
                baselineFile.set(baseline)
                applicationId.set(variant.applicationId)
                aapt2.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/aapt2") })
                ledgerFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/baseline-candidate/resource-ledger.json"))
                stableIds.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/baseline-candidate/stable-ids.txt"))
            }
            def analysis = project.tasks.register("analyze${cap}ParavoidResources", AnalyzeResourcesTask) {
                group = 'paravoid'
                description = 'Computes manifest/explicit pinned-resource closure without changing APK contents.'
                apkDirectory.set(apkInputs)
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
            def split = project.tasks.register("split${cap}ParavoidResources", SplitResourcesTask) {
                group = 'paravoid'
                description = 'Builds validated shell-only and complete payload resource containers without modifying the installed APK.'
                apkDirectory.set(apkInputs)
                applicationId.set(variant.applicationId)
                pinnedResources.set(extension.pinnedResources)
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/resource-boundary.json"))
                aapt2.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/aapt2") })
                zipalign.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/zipalign") })
                shellResources.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resources/shell-resources.apk"))
                payloadResources.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resources/payload-resources.apk"))
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resources/split-report.json"))
            }
            def nativeValidation = project.tasks.register("validate${cap}ParavoidNativeLibraries", ValidateNativeLibrariesTask) {
                nativeLibraries.from(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS.INSTANCE))
                minSdk.set(variant.minSdk.apiLevel)
                validationFile.set(project.layout.buildDirectory.file("intermediates/paravoid/${variant.name}/native-validation.txt"))
            }
            def manifest = project.tasks.register("prepare${cap}ParavoidManifest", ApplicationManifestTask) {
                it.complete.set(complete)
                debugHttpAllowed.set(complete ? extension.updates.debugHttpAllowed : project.providers.provider { false })
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
            def javaResources = project.tasks.register("package${cap}ParavoidJavaResources", PackageJavaResourcesTask) {
                apkDirectory.set(apkInputs)
                it.javaResources.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/java-resources.jar"))
            }
            def nativeResources = project.tasks.register("package${cap}ParavoidNativeResources", PackageNativeResourcesTask) {
                apkDirectory.set(apkInputs)
                nativeArchive.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/native-libraries.zip"))
            }
            def contract = project.tasks.register("generate${cap}ParavoidContract", GenerateShellContractTask) {
                apkDirectory.set(apkInputs)
                boundaryFile.set(analysis.flatMap { it.boundaryFile })
                ledgerFile.set(ledger.flatMap { it.ledgerFile })
                shellClasses.set(pack.flatMap { it.shellClasses })
                manifestFile.set(manifest.flatMap { it.outputManifest })
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/shell-contract.json"))
                minSdk.set(variant.minSdk.apiLevel)
                toolchain.set([agp: Version.ANDROID_GRADLE_PLUGIN_VERSION,
                    buildTools: android.buildToolsVersion.toString(), compileSdk: android.compileSdk.toString()])
                contractFile.set(project.layout.buildDirectory.file(complete
                    ? "intermediates/paravoid/${variant.name}/embedded-boundary.json"
                    : "outputs/paravoid/${variant.name}/baseline-candidate/shell-contract.json"))
            }
            project.tasks.register("export${cap}ParavoidBaseline") {
                group = 'paravoid'
                description = 'Exports ledger, resource boundary and embedded shell contract candidates for review.'
                dependsOn(contract)
            }
            def completePolicy = project.tasks.register("generate${cap}ParavoidCompletePolicy", GenerateCompletePolicyTask) {
                group = 'paravoid'
                installedBoundary.set(contract.flatMap { it.contractFile })
                trustPolicyFile.set(extension.updates.trustPolicyFile)
                bootstrap.set(extension.bootstrap)
                updatesEnabled.set(extension.updates.enabled)
                baseUrl.set(extension.updates.baseUrl)
                channel.set(extension.updates.channel)
                authentication.set(extension.updates.authentication)
                debugHttpAllowed.set(extension.updates.debugHttpAllowed)
                debuggable.set(android.buildTypes.getByName(variant.buildType).debuggable)
                releaseBuild.set(variant.buildType == 'release')
                policyFile.set(project.layout.buildDirectory.file(complete
                    ? "outputs/paravoid/${variant.name}/baseline-candidate/shell-contract.json"
                    : "outputs/paravoid/${variant.name}/complete-policy/shell-policy.json"))
            }
            if (complete) project.tasks.named("export${cap}ParavoidBaseline") { dependsOn(completePolicy) }
            def packagingReport = project.tasks.register("report${cap}ParavoidPackaging", GeneratePackagingReportTask) {
                group = 'paravoid'
                description = 'Exports public ownership, compatibility and limitation reports without modifying accepted baselines.'
                policyFile.set(completePolicy.flatMap { it.policyFile })
                boundaryFile.set(analysis.flatMap { it.boundaryFile })
                ledgerFile.set(ledger.flatMap { it.ledgerFile })
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/shell-contract.json"))
                it.packaging.set(packaging)
                exportedContract.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/shell-contract.json"))
                exportedLedger.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resource-ledger.json"))
                jsonReport.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/packaging-report.json"))
                textReport.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/packaging-report.txt"))
            }
            if (complete) project.tasks.named("export${cap}ParavoidBaseline") {
                description = 'Exports ledger, resource boundary, complete shell contract and public packaging reports for review.'
                dependsOn(packagingReport)
            }
            project.tasks.register("export${cap}ParavoidCompleteBaseline", org.gradle.api.tasks.Sync) {
                group = 'paravoid'
                description = 'Exports complete-policy baseline candidates; never changes accepted baselines.'
                from(ledger.flatMap { it.ledgerFile })
                from(ledger.flatMap { it.stableIds })
                from(analysis.flatMap { it.boundaryFile })
                from(completePolicy.flatMap { it.policyFile }) { rename { 'shell-contract.json' } }
                into(project.layout.buildDirectory.dir("outputs/paravoid/${variant.name}/complete-baseline-candidate"))
            }
            def completeCheck = project.tasks.register("check${cap}ParavoidVpkContract", CheckShellContractTask) {
                dependsOn(packagingReport)
                group = 'verification'
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/shell-contract.json"))
                candidateFile.set(completePolicy.flatMap { it.policyFile })
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/vpk-contract-check.txt"))
            }
            def completeVpk = project.tasks.register("package${cap}ParavoidVpk", PackageCompleteVpkTask) {
                group = 'paravoid'
                description = 'Produces a signed complete VPK; does not change shell startup or ordinary APK outputs.'
                dependsOn(completeCheck)
                policyFile.set(completePolicy.flatMap { it.policyFile })
                codeBundle.set(pack.flatMap { it.bundleFile })
                resourcesApk.set(split.flatMap { it.payloadResources })
                it.javaResources.set(javaResources.flatMap { it.javaResources })
                nativeArchive.set(nativeResources.flatMap { it.nativeArchive })
                ledgerFile.set(ledger.flatMap { it.ledgerFile })
                payloadVersion.set(extension.payloadVersion)
                releaseId.set(extension.releaseId)
                keyId.set(extension.signing.keyId)
                privateKeyFile.set(extension.signing.privateKeyFile)
                minSdk.set(variant.minSdk.apiLevel)
                debuggable.set(android.buildTypes.getByName(variant.buildType).debuggable)
                vpkFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/payload.vpk"))
                releaseFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/release.json"))
                digestFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/payload.sha256"))
                legacyDigestFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/payload.vpk.sha256"))
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/vpk-report.json"))
            }
            if (complete) {
                def shell = project.tasks.register("package${cap}ParavoidCompleteShell", PackageCompleteShellTask) {
                    group = 'paravoid'
                    dependsOn(completeCheck)
                    it.completePolicy.set(completePolicy.flatMap { it.policyFile })
                    if (extension.bootstrap.get() == 'embedded') it.completeVpk.set(completeVpk.flatMap { it.vpkFile })
                    shellResources.set(split.flatMap { it.shellResources })
                    payloadResources.set(split.flatMap { it.payloadResources })
                    javaResourceArchive.set(javaResources.flatMap { it.javaResources })
                    nativeResourceArchive.set(nativeResources.flatMap { it.nativeArchive })
                    shellClasses.set(pack.flatMap { it.shellClasses })
                    manifestFile.set(manifest.flatMap { it.outputManifest })
                    contractFile.set(completePolicy.flatMap { it.policyFile })
                    minSdk.set(variant.minSdk.apiLevel)
                    zipalign.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/zipalign") })
                    def signing = android.buildTypes.getByName(variant.buildType).signingConfig
                    if (signing != null) {
                        keyStoreFile.set(signing.storeFile); storePassword.set(signing.storePassword)
                        keyPassword.set(signing.keyPassword); keyAlias.set(signing.keyAlias); storeType.set(signing.storeType)
                    }
                    shellApk.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/shell.apk"))
                }
                def shellRequest = variant.artifacts.use(shell).wiredWithDirectories({ it.apkDirectory }, { it.outputDirectory })
                    .toTransformMany(SingleArtifact.APK.INSTANCE)
                shell.configure { transformation.set(shellRequest) }
            }
            def contractCheck = project.tasks.register("check${cap}ParavoidContract", CheckShellContractTask) {
                if (complete) dependsOn(packagingReport)
                group = 'verification'
                baselineFile.set(extension.baselineDirectory.file("${variant.name}/shell-contract.json"))
                candidateFile.set(complete ? completePolicy.flatMap { it.policyFile } : contract.flatMap { it.contractFile })
                reportFile.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/shell-contract-check.txt"))
            }
            project.tasks.register("package${cap}ParavoidResourceShell", PackageResourceShellTask) {
                dependsOn(contractCheck)
                group = 'paravoid'
                description = 'Signs an embedded-resource shell APK (not a complete VPK); leaves ordinary APK outputs unchanged.'
                apkDirectory.set(apkInputs)
                shellResources.set(split.flatMap { it.shellResources })
                payloadResources.set(split.flatMap { it.payloadResources })
                javaResourceArchive.set(javaResources.flatMap { it.javaResources })
                nativeResourceArchive.set(nativeResources.flatMap { it.nativeArchive })
                shellClasses.set(pack.flatMap { it.shellClasses })
                manifestFile.set(manifest.flatMap { it.outputManifest })
                contractFile.set(contract.flatMap { it.contractFile })
                minSdk.set(variant.minSdk.apiLevel)
                zipalign.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/zipalign") })
                def signing = android.buildTypes.getByName(variant.buildType).signingConfig
                if (signing != null) {
                    keyStoreFile.set(signing.storeFile)
                    storePassword.set(signing.storePassword)
                    keyPassword.set(signing.keyPassword)
                    keyAlias.set(signing.keyAlias)
                    storeType.set(signing.storeType)
                }
                shellApk.set(project.layout.buildDirectory.file("outputs/paravoid/${variant.name}/resource-shell.apk"))
            }
        }
    }
}
