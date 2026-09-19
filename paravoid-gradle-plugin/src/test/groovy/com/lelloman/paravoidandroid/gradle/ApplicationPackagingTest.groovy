package com.lelloman.paravoidandroid.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import static org.junit.Assert.*

class ApplicationPackagingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void producesTwoModesAndTransformsOnlyPayloadApplication() {
        File root = fixture()
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug', ':app:bundleNormalRelease', ':app:bundleParavoidAndroidRelease').build()
        File normal = new File(root, 'app/build/outputs/apk/normal/debug/app-normal-debug.apk')
        File shell = new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')
        new ZipFile(normal).withCloseable { zip ->
            assertNull(zip.getEntry('assets/paravoid/module.zip'))
            assertTrue(dexText(zip).contains('Lexample/MainActivity;'))
            assertTrue(dexText(zip).contains('Lexample/MyApplication;'))
        }
        new ZipFile(shell).withCloseable { zip ->
            String host = dexText(zip)
            assertFalse(host.contains('Lexample/MainActivity;'))
            assertFalse(host.contains('Lexample/MyApplication;'))
            assertFalse(host.contains('Lexample/dependency/Logic;'))
            assertTrue(host.contains('Lcom/lelloman/paravoidandroid/runtime/LauncherActivity;'))
            assertNotNull(zip.getEntry('assets/paravoid/module.zip'))
            String payload = ''
            new ZipInputStream(zip.getInputStream(zip.getEntry('assets/paravoid/module.zip'))).withCloseable { module ->
                def entry
                while ((entry = module.nextEntry) != null) {
                    if (entry.name == 'classes.dex') payload = new String(module.readAllBytes(), 'ISO-8859-1')
                }
            }
            assertTrue(payload.contains('Lexample/MyApplication;'))
            assertTrue(payload.contains('Lexample/MainActivity;'))
            assertTrue(payload.contains('Lexample/dependency/Logic;'))
            assertTrue(payload.contains('Lcom/lelloman/paravoidandroid/runtime/PayloadApplication;'))
            assertFalse(payload.contains('Lcom/lelloman/paravoidandroid/runtime/ParavoidAndroidApplication;'))
        }
        String manifest = new File(root, 'app/build/intermediates/merged_manifest/paravoidAndroidDebug/prepareParavoidAndroidDebugParavoidManifest/AndroidManifest.xml').text
        assertTrue(manifest.contains('runtime.ShellApplication'))
        assertTrue(manifest.contains('runtime.ParavoidComponentFactory'))
        assertTrue(manifest.contains('runtime.LauncherActivity'))
        assertTrue(manifest.contains('example.MainActivity'))
        def second = run(root, ':app:assembleParavoidAndroidDebug').build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':app:packageParavoidAndroidDebugParavoidApplication').outcome)
    }

    @Test void rejectsAdditionalActivityFromDependencyManifest() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':extra'\n"
        write(root, 'extra/build.gradle', "plugins { id 'com.android.library' }; android { namespace 'example.extra'; compileSdk 36; defaultConfig { minSdk 28 } }")
        write(root, 'extra/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="example.Other" /></application></manifest>')
        new File(root, 'app/build.gradle') << "\ndependencies { implementation project(':extra') }\n"
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output.contains('exactly one user Activity'))
    }

    @Test void optionalTransformerRunsBeforeRemappingAndTracksItsInputs() {
        File root = fixture()
        new File(root, 'app/build.gradle') << '''
            class TestTransformer implements com.lelloman.paravoidandroid.gradle.PayloadTransformer {
                @org.gradle.api.tasks.Input int marker
                void transform(Map<String, byte[]> classes) {
                    assert classes.containsKey('example/dependency/Logic.class')
                    assert new org.objectweb.asm.ClassReader(classes['example/MyApplication.class']).superName ==
                        'com/lelloman/paravoidandroid/runtime/ParavoidAndroidApplication'
                    println "payload-transform-marker=${marker}"
                }
            }
            tasks.withType(com.lelloman.paravoidandroid.gradle.PackageApplicationTask).configureEach {
                payloadTransformers.add(new TestTransformer(marker: providers.gradleProperty('marker').get().toInteger()))
            }
        '''
        assertFalse(run(root, ':app:assembleNormalDebug', '-Pmarker=1').build().output.contains('payload-transform-marker='))
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug', '-Pmarker=1').build().output.contains('payload-transform-marker=1'))
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, ':app:assembleParavoidAndroidDebug', '-Pmarker=1').build()
            .task(':app:packageParavoidAndroidDebugParavoidApplication').outcome)
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug', '-Pmarker=2').build().output.contains('payload-transform-marker=2'))
    }

    @Test void preservesInstalledResourcesAndRepackagesChangedAssets() {
        File root = fixture()
        write(root, 'app/src/main/res/values/strings.xml', '<resources><string name="fixture_name">Resource fixture</string></resources>')
        write(root, 'app/src/main/res/raw/note.txt', 'raw fixture')
        write(root, 'app/src/main/assets/nested/catalog.json', '{"version":1}')
        write(root, 'app/src/main/resources/fixture/app.properties', 'origin=app')
        write(root, 'logic/src/main/resources/fixture/library.properties', 'origin=library')
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug', ':app:bundleParavoidAndroidRelease').build()
        ['normal', 'paravoidAndroid'].each { flavor ->
            new ZipFile(new File(root, "app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk")).withCloseable { apk ->
                assertNotNull(apk.getEntry('resources.arsc'))
                ['res/raw/note.txt': 'raw fixture', 'assets/nested/catalog.json': '{"version":1}',
                 'fixture/app.properties': 'origin=app', 'fixture/library.properties': 'origin=library'].each { name, expected ->
                    assertNotNull(name, apk.getEntry(name))
                    assertEquals(expected, apk.getInputStream(apk.getEntry(name)).withCloseable { it.getText('UTF-8') })
                }
            }
        }
        new ZipFile(new File(root, 'app/build/outputs/bundle/paravoidAndroidRelease/app-paravoidAndroid-release.aab')).withCloseable { aab ->
            ['base/resources.pb', 'base/res/raw/note.txt', 'base/assets/nested/catalog.json',
             'base/root/fixture/app.properties', 'base/root/fixture/library.properties',
             'base/assets/paravoid/module.zip'].each { assertNotNull(it, aab.getEntry(it)) }
        }
        File payload = new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/module.zip')
        byte[] previous = payload.bytes
        new ZipFile(payload).withCloseable { zip ->
            assertEquals(['module.properties', 'classes.dex'] as Set, zip.entries().collect { it.name } as Set)
        }
        write(root, 'app/src/main/assets/nested/catalog.json', '{"version":2}')
        def rebuilt = run(root, ':app:assembleParavoidAndroidDebug').build()
        assertEquals(TaskOutcome.UP_TO_DATE, rebuilt.task(':app:packageParavoidAndroidDebugParavoidApplication').outcome)
        assertArrayEquals('Asset changes currently affect the installed APK, not the DEX payload', previous, payload.bytes)
        new ZipFile(new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')).withCloseable { apk ->
            assertEquals('{"version":2}', apk.getInputStream(apk.getEntry('assets/nested/catalog.json')).withCloseable { it.getText('UTF-8') })
        }
    }

    @Test void rejectsOrdinaryCustomApplication() {
        File root = fixture()
        write(root, 'app/src/main/java/example/MyApplication.java', 'package example; public class MyApplication extends android.app.Application {}')
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output.contains('must extend ParavoidAndroidApplication'))
    }

    @Test void transformsIndirectApplicationSuperclass() {
        File root = fixture()
        write(root, 'app/src/main/java/example/GeneratedApplication.java',
            'package example; public class GeneratedApplication extends com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication {}')
        File app = new File(root, 'app/src/main/java/example/MyApplication.java')
        app.text = app.text.replace('com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication', 'GeneratedApplication')
        run(root, ':app:assembleParavoidAndroidDebug').build()
        new ZipFile(new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/module.zip')).withCloseable { zip ->
            String payload = dexText(zip)
            assertTrue(payload.contains('Lexample/GeneratedApplication;'))
            assertTrue(payload.contains('Lcom/lelloman/paravoidandroid/runtime/PayloadApplication;'))
            assertFalse(payload.contains('Lcom/lelloman/paravoidandroid/runtime/ParavoidAndroidApplication;'))
        }
    }

    @Test void ignoresModuleDescriptorsButStillRejectsDuplicateClasses() {
        File root = fixture()
        new File(root, 'app/build.gradle') << "\ndependencies { implementation fileTree(dir: 'libs', include: ['*.jar']) }\n"
        ['one', 'two'].each { name ->
            File jar = new File(root, "app/libs/${name}.jar")
            jar.parentFile.mkdirs()
            ClassWriter descriptor = new ClassWriter(0)
            descriptor.visit(Opcodes.V9, Opcodes.ACC_MODULE, 'module-info', null, null, null)
            descriptor.visitModule("example.${name}", 0, null).visitEnd()
            descriptor.visitEnd()
            new ZipOutputStream(new FileOutputStream(jar)).withCloseable { zip ->
                ['module-info.class', 'META-INF/versions/9/module-info.class',
                 'META-INF/versions/11/module-info.class'].each { path ->
                    zip.putNextEntry(new ZipEntry(path))
                    zip.write(descriptor.toByteArray())
                    zip.closeEntry()
                }
            }
        }
        run(root, ':app:assembleParavoidAndroidDebug').build()
        new ZipFile(new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/module.zip')).withCloseable { zip ->
            assertFalse(dexText(zip).contains('module-info'))
        }

        // Real duplicate classes must remain an error, even when byte-for-byte identical.
        File logicJar = new File(root, 'logic/build/libs/logic.jar')
        assertTrue(logicJar.isFile())
        new File(root, 'app/libs/duplicate.jar').bytes = logicJar.bytes
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output
            .contains('Duplicate application class: example/dependency/Logic.class'))
    }

    @Test void supportsDefaultApplicationWithoutCustomInitializer() {
        File root = fixture()
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('android:name="example.MyApplication"', '')
        run(root, ':app:assembleParavoidAndroidDebug').build()
        assertFalse(new File(root, 'app/build/intermediates/merged_manifest/paravoidAndroidDebug/prepareParavoidAndroidDebugParavoidManifest/AndroidManifest.xml').text.contains('paravoid.application'))
    }

    private File fixture() {
        File root = temporary.newFolder()
        File repo = new File(System.getProperty('paravoid.repo'))
        String sdk = System.getenv('ANDROID_HOME') ?: System.getenv('ANDROID_SDK_ROOT')
        if (!sdk) {
            Properties local = new Properties()
            new File(repo, 'local.properties').withInputStream { local.load(it) }
            sdk = local.getProperty('sdk.dir')
        }
        write(root, 'local.properties', "sdk.dir=${sdk}\n")
        write(root, 'gradle.properties', 'android.useAndroidX=true\n')
        write(root, 'settings.gradle', """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = 'application-fixture'
            include ':app', ':paravoid-api', ':paravoid-runtime', ':logic'
        """)
        ['paravoid-api', 'paravoid-runtime'].each { module ->
            write(root, "${module}/build.gradle", new File(repo, "${module}/build.gradle").text)
            File source = new File(repo, "${module}/src/main")
            source.eachFileRecurse { file ->
                if (file.isFile()) write(root, "${module}/src/main/" + source.toPath().relativize(file.toPath()), file.text)
            }
        }
        write(root, 'app/build.gradle', """
            plugins { id 'com.lelloman.paravoid' }
            android { namespace 'example'; compileSdk 36; defaultConfig { applicationId 'example.fixture'; minSdk 28; targetSdk 36; versionCode 1; versionName '1.0' } }
            dependencies { implementation project(':paravoid-runtime'); implementation project(':logic') }
        """)
        write(root, 'logic/build.gradle', "plugins { id 'java-library' }; java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }")
        write(root, 'logic/src/main/java/example/dependency/Logic.java', 'package example.dependency; public class Logic { public static int count() { return 42; } }')
        write(root, 'app/src/main/AndroidManifest.xml', '''
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application android:name="example.MyApplication">
                <activity android:name="example.MainActivity" android:exported="true">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
              </application>
            </manifest>
        ''')
        write(root, 'app/src/main/java/example/MyApplication.java', '''
            package example;
            public class MyApplication extends com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication {
                public void onCreate() { super.onCreate(); getSharedPreferences("state", 0); }
            }
        ''')
        write(root, 'app/src/main/java/example/MainActivity.java', 'package example; public class MainActivity extends android.app.Activity { int count = example.dependency.Logic.count(); }')
        return root
    }

    private static String dexText(ZipFile zip) {
        zip.entries().findAll { it.name ==~ /classes\d*\.dex/ }.collect {
            zip.getInputStream(it).withCloseable { new String(it.readAllBytes(), 'ISO-8859-1') }
        }.join('')
    }
    private static GradleRunner run(File root, String... tasks) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(
            tasks.toList() + ['--stacktrace', '--max-workers=2', '--gradle-user-home', System.getProperty('paravoid.gradleUserHome')])
    }
    private static void write(File root, String path, String content) {
        File file = new File(root, path)
        file.parentFile.mkdirs()
        file.text = content
    }
}
