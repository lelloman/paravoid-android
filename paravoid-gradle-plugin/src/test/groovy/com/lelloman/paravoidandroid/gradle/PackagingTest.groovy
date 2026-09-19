package com.lelloman.paravoidandroid.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import static org.junit.Assert.*

class PackagingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void packagesDexAsAssetAndReusesTaskOutputs() {
        File root = fixture()
        def first = runner(root, ':shell:assembleDebug').build()
        assertEquals(TaskOutcome.SUCCESS, first.task(':module:bundleDebugParavoidModule').outcome)
        File apk = new File(root, 'shell/build/outputs/apk/debug/shell-debug.apk')
        new ZipFile(apk).withCloseable { zip ->
            assertNotNull(zip.getEntry('assets/paravoid/module.zip'))
            zip.entries().findAll { it.name ==~ /classes\d*\.dex/ }.each { item ->
                String content = zip.getInputStream(item).withCloseable { new String(it.readAllBytes(), 'ISO-8859-1') }
                assertFalse('Module implementation leaked into shell DEX', content.contains('Lexample/Entry;'))
            }
            boolean foundDex = false
            new ZipInputStream(zip.getInputStream(zip.getEntry('assets/paravoid/module.zip'))).withCloseable { module ->
                def entry
                while ((entry = module.nextEntry) != null) {
                    if (entry.name == 'classes.dex') {
                        assertTrue(new String(module.readAllBytes(), 'ISO-8859-1').contains('Lexample/Entry;'))
                        foundDex = true
                    }
                }
            }
            assertTrue(foundDex)
        }
        def second = runner(root, ':shell:assembleDebug').build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':module:bundleDebugParavoidModule').outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':shell:embedDebugParavoidModule').outcome)
    }

    @Test void rejectsMissingEntryPoint() {
        File root = fixture()
        new File(root, 'module/build.gradle') << "\nparavoidModule.entryPoint = 'example.Missing'\n"
        assertTrue(runner(root, ':module:bundleDebugParavoidModule').buildAndFail().output
            .contains('Entry point example.Missing is not present in the module.'))
    }

    @Test void rejectsResourcesInsteadOfSilentlyDroppingThem() {
        File root = fixture()
        write(root, 'module/src/main/assets/message.txt', 'This asset must not disappear silently.')
        assertTrue(runner(root, ':module:bundleDebugParavoidModule').buildAndFail().output
            .contains('Unsupported module content: assets/message.txt'))
    }

    @Test void rejectsUnpackagedRuntimeDependencies() {
        File root = fixture()
        new ZipOutputStream(new FileOutputStream(new File(root, 'module/extra.jar'))).close()
        new File(root, 'module/build.gradle') << "\ndependencies { implementation files('extra.jar') }\n"
        assertTrue(runner(root, ':module:bundleDebugParavoidModule').buildAndFail().output
            .contains('does not bundle runtime dependencies'))
    }

    private File fixture() {
        File root = temporary.newFolder()
        String sdk = System.getenv('ANDROID_HOME') ?: System.getenv('ANDROID_SDK_ROOT')
        if (!sdk) {
            Properties local = new Properties()
            File localFile = new File(System.getProperty('paravoid.repo'), 'local.properties')
            if (localFile.exists()) localFile.withInputStream { local.load(it) }
            sdk = local.getProperty('sdk.dir')
        }
        assertNotNull('Set ANDROID_HOME or the repository local.properties sdk.dir to run plugin tests.', sdk)
        Properties properties = new Properties()
        properties.setProperty('sdk.dir', sdk)
        new File(root, 'local.properties').withOutputStream { properties.store(it, null) }
        write(root, 'settings.gradle', '''
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = 'fixture'
            include ':module', ':shell'
        ''')
        write(root, 'module/build.gradle', '''
            plugins { id 'com.lelloman.paravoid.module' }
            android { namespace 'example.module'; compileSdk 36; defaultConfig { minSdk 26 } }
            paravoidModule { entryPoint = 'example.Entry' }
        ''')
        write(root, 'module/src/main/AndroidManifest.xml', '<manifest />')
        write(root, 'module/src/main/java/example/Entry.java', 'package example; public class Entry {}')
        write(root, 'shell/build.gradle', '''
            plugins { id 'com.lelloman.paravoid.shell' }
            android {
                namespace 'example.shell'
                compileSdk 36
                defaultConfig { minSdk 26; targetSdk 36 }
            }
            dependencies { paravoidBundle project(':module') }
        ''')
        write(root, 'shell/src/main/AndroidManifest.xml', '<manifest><application /></manifest>')
        return root
    }

    private static GradleRunner runner(File root, String task) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(task, '--stacktrace', '--max-workers=2',
                '--gradle-user-home', System.getProperty('paravoid.gradleUserHome'))
    }

    private static void write(File root, String path, String text) {
        File file = new File(root, path)
        file.parentFile.mkdirs()
        file.text = text
    }
}
