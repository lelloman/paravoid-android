package com.lelloman.paravoidandroid.hilt.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import static org.junit.Assert.*

class ParavoidHiltPluginTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void bridgeIsPayloadOnlyAndRemovingThePluginDisablesAdaptation() {
        File root = fixture()
        run(root, 'assembleNormalDebug', 'assembleParavoidAndroidDebug').build()
        String bridge = 'L' + HiltLookupGenerator.NAME + ';'
        ['normal', 'paravoidAndroid'].each { flavor ->
            new ZipFile(apk(root, flavor)).withCloseable { zip -> assertFalse(dexText(zip).contains(bridge)) }
        }
        assertTrue(payloadText(root).contains(bridge))
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, 'assembleParavoidAndroidDebug').build()
            .task(':packageParavoidAndroidDebugParavoidApplication').outcome)
        def disabled = run(root, 'assembleParavoidAndroidDebug', '-PhiltProbeDisableIntegration=true').build()
        assertEquals(TaskOutcome.SUCCESS, disabled.task(':packageParavoidAndroidDebugParavoidApplication').outcome)
        assertFalse(payloadText(root).contains(bridge))
    }

    @Test void rejectsMissingHiltPluginWithAnActionableMessage() {
        File root = fixture()
        File build = new File(root, 'build.gradle')
        build.text = build.text.replace("id 'com.google.dagger.hilt.android'", '')
        assertTrue(run(root, 'assembleParavoidAndroidDebug').buildAndFail().output
            .contains('paravoid-hilt requires the com.google.dagger.hilt.android plugin'))
    }

    @Test void validatesSelectedVersionAfterDependencyResolution() {
        File root = fixture()
        new File(root, 'build.gradle') << '''
            configurations.configureEach { resolutionStrategy.force 'com.google.dagger:hilt-core:2.60.1' }
            tasks.register('validateHiltSelection') {
                doLast {
                    // Exercise the same resolved-version provider used by packaging, without
                    // compiling an intentionally unsupported dependency combination first.
                    tasks.named('packageParavoidAndroidDebugParavoidApplication').get()
                        .payloadTransformers.get().first().transform([:])
                }
            }
        '''
        String output = run(root, 'validateHiltSelection').buildAndFail().output
        assertTrue(output, output.contains('resolved hilt-core: 2.60.1'))
    }

    private File fixture() {
        File root = temporary.newFolder()
        File repo = new File(System.getProperty('paravoid.repo'))
        copy(new File(repo, 'compatibility/hilt/src'), new File(root, 'src'))
        write(root, 'build.gradle', new File(repo, 'compatibility/hilt/build.gradle').text
            .replace("id 'com.google.dagger.hilt.android' version '2.57.2'", "id 'com.google.dagger.hilt.android'"))
        write(root, 'gradle.properties', 'android.useAndroidX=true\n')
        String sdk = System.getenv('ANDROID_HOME') ?: System.getenv('ANDROID_SDK_ROOT')
        if (!sdk) {
            Properties local = new Properties()
            new File(repo, 'local.properties').withInputStream { local.load(it) }
            sdk = local.getProperty('sdk.dir')
        }
        write(root, 'local.properties', "sdk.dir=${sdk}\n")
        write(root, 'settings.gradle', """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = 'hilt-compatibility'
            include ':paravoid-api', ':paravoid-runtime', ':paravoid-contract'
        """)
        ['paravoid-api', 'paravoid-runtime', 'paravoid-contract'].each { module ->
            write(root, "${module}/build.gradle", new File(repo, "${module}/build.gradle").text)
            copy(new File(repo, "${module}/src/main"), new File(root, "${module}/src/main"))
        }
        write(root, 'gradle/local-publication.gradle', new File(repo, 'gradle/local-publication.gradle').text)
        copy(new File(repo, 'delivery/src'), new File(root, 'delivery/src'))
        copy(new File(repo, 'delivery/android/src'), new File(root, 'delivery/android/src'))
        return root
    }

    private static File apk(File root, String flavor) {
        new File(root, "build/outputs/apk/${flavor}/debug/hilt-compatibility-${flavor}-debug.apk")
    }
    private static String payloadText(File root) {
        new ZipFile(new File(root, 'build/outputs/paravoid/paravoidAndroidDebug/module.zip')).withCloseable { dexText(it) }
    }
    private static String dexText(ZipFile zip) {
        zip.entries().findAll { it.name ==~ /classes\d*\.dex/ }.collect {
            zip.getInputStream(it).withCloseable { new String(it.readAllBytes(), 'ISO-8859-1') }
        }.join('')
    }
    private static GradleRunner run(File root, String... tasks) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(tasks.toList() +
            ['--stacktrace', '--max-workers=2', '--gradle-user-home', System.getProperty('paravoid.gradleUserHome')])
    }
    private static void copy(File source, File target) {
        source.eachFileRecurse { file ->
            if (file.isFile()) write(target, source.toPath().relativize(file.toPath()).toString(), file.text)
        }
    }
    private static void write(File root, String path, String content) {
        File file = new File(root, path)
        file.parentFile.mkdirs()
        file.text = content
    }
}
