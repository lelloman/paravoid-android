package com.lelloman.paravoidandroid.work.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import static org.junit.Assert.*

class ParavoidWorkPluginTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()
    @Test void optionalBridgeStaysInPayloadAndTracksOptOut() {
        File root = fixture()
        run(root, 'assembleNormalDebug', 'assembleParavoidAndroidDebug').build()
        String bridge = 'L' + WorkLookupGenerator.NAME + ';'
        ['normal', 'paravoidAndroid'].each { flavor ->
            new ZipFile(new File(root, "build/outputs/apk/${flavor}/debug/storage-compatibility-${flavor}-debug.apk")).withCloseable {
                assertFalse(dexText(it).contains(bridge))
                if (flavor == 'paravoidAndroid') assertFalse(dexText(it).contains('Landroidx/work/impl/WorkManagerImpl;'))
            }
        }
        File payload = new File(root, 'build/outputs/paravoid/paravoidAndroidDebug/module.zip')
        new ZipFile(payload).withCloseable { assertTrue(dexText(it).contains(bridge)) }
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, 'assembleParavoidAndroidDebug').build()
            .task(':packageParavoidAndroidDebugParavoidApplication').outcome)
        assertEquals(TaskOutcome.SUCCESS, run(root, 'assembleParavoidAndroidDebug', '-PworkProbeDisableIntegration').build()
            .task(':packageParavoidAndroidDebugParavoidApplication').outcome)
        new ZipFile(payload).withCloseable { assertFalse(dexText(it).contains(bridge)) }
    }
    @Test void validatesSelectedUnsupportedAndMissingRuntimeBeforeTransformation() {
        File root = fixture()
        new File(root, 'build.gradle') << "\nconfigurations.configureEach { resolutionStrategy.force 'androidx.work:work-runtime:2.10.0' }\n"
        assertTrue(run(root, 'validateWorkSelection').buildAndFail().output.contains('resolved work-runtime: 2.10.0'))
        File build = new File(root, 'build.gradle')
        build.text = build.text.replace("implementation 'androidx.work:work-runtime:2.10.1'", '')
        assertTrue(run(root, 'validateWorkSelection').buildAndFail().output.contains('resolved work-runtime: missing'))
    }
    private File fixture() {
        File root = temporary.newFolder()
        File repo = new File(System.getProperty('paravoid.repo'))
        copy(new File(repo, 'compatibility/storage/src'), new File(root, 'src'))
        write(root, 'build.gradle', new File(repo, 'compatibility/storage/build.gradle').text + '''
            tasks.register('validateWorkSelection') {
                doLast {
                    tasks.named('packageParavoidAndroidDebugParavoidApplication').get()
                        .payloadTransformers.get().first().transform([:])
                }
            }
        ''')
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
            rootProject.name = 'storage-compatibility'
            include ':paravoid-api', ':paravoid-runtime', ':paravoid-contract', ':paravoid-recovery-api'
        """)
        ['paravoid-api', 'paravoid-runtime', 'paravoid-contract', 'paravoid-recovery-api'].each { module ->
            write(root, "${module}/build.gradle", new File(repo, "${module}/build.gradle").text)
            copy(new File(repo, "${module}/src/main"), new File(root, "${module}/src/main"))
        }
        write(root, 'gradle/local-publication.gradle', new File(repo, 'gradle/local-publication.gradle').text)
        copy(new File(repo, 'delivery/src'), new File(root, 'delivery/src'))
        copy(new File(repo, 'delivery/android/src'), new File(root, 'delivery/android/src'))
        return root
    }
    private static String dexText(ZipFile zip) {
        zip.entries().findAll { it.name ==~ /classes\d*\.dex/ }.collect {
            zip.getInputStream(it).withCloseable { new String(it.readAllBytes(), 'ISO-8859-1') }
        }.join('')
    }
    private static GradleRunner run(File root, String... tasks) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(tasks.toList() +
            ['-PconfiguredWork', '--stacktrace', '--max-workers=2', '--gradle-user-home', System.getProperty('paravoid.gradleUserHome')])
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
