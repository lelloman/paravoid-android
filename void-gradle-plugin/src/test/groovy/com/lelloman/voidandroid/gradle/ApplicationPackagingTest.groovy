package com.lelloman.voidandroid.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import static org.junit.Assert.*

class ApplicationPackagingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void producesTwoModesAndTransformsOnlyPayloadApplication() {
        File root = fixture()
        run(root, ':app:assembleNormalDebug', ':app:assembleVoidAndroidDebug', ':app:bundleNormalRelease', ':app:bundleVoidAndroidRelease').build()
        File normal = new File(root, 'app/build/outputs/apk/normal/debug/app-normal-debug.apk')
        File shell = new File(root, 'app/build/outputs/apk/voidAndroid/debug/app-voidAndroid-debug.apk')
        new ZipFile(normal).withCloseable { zip ->
            assertNull(zip.getEntry('assets/void/module.zip'))
            assertTrue(dexText(zip).contains('Lexample/MainActivity;'))
            assertTrue(dexText(zip).contains('Lexample/MyApplication;'))
        }
        new ZipFile(shell).withCloseable { zip ->
            String host = dexText(zip)
            assertFalse(host.contains('Lexample/MainActivity;'))
            assertFalse(host.contains('Lexample/MyApplication;'))
            assertFalse(host.contains('Lexample/dependency/Logic;'))
            assertTrue(host.contains('Lcom/lelloman/voidandroid/runtime/LauncherActivity;'))
            assertNotNull(zip.getEntry('assets/void/module.zip'))
            String payload = ''
            new ZipInputStream(zip.getInputStream(zip.getEntry('assets/void/module.zip'))).withCloseable { module ->
                def entry
                while ((entry = module.nextEntry) != null) {
                    if (entry.name == 'classes.dex') payload = new String(module.readAllBytes(), 'ISO-8859-1')
                }
            }
            assertTrue(payload.contains('Lexample/MyApplication;'))
            assertTrue(payload.contains('Lexample/MainActivity;'))
            assertTrue(payload.contains('Lexample/dependency/Logic;'))
            assertTrue(payload.contains('Lcom/lelloman/voidandroid/runtime/PayloadApplication;'))
            assertFalse(payload.contains('Lcom/lelloman/voidandroid/runtime/VoidAndroidApplication;'))
        }
        String manifest = new File(root, 'app/build/intermediates/merged_manifest/voidAndroidDebug/prepareVoidAndroidDebugVoidManifest/AndroidManifest.xml').text
        assertTrue(manifest.contains('runtime.ShellApplication'))
        assertTrue(manifest.contains('runtime.VoidComponentFactory'))
        assertTrue(manifest.contains('runtime.LauncherActivity'))
        assertTrue(manifest.contains('example.MainActivity'))
        def second = run(root, ':app:assembleVoidAndroidDebug').build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':app:packageVoidAndroidDebugVoidApplication').outcome)
    }

    @Test void rejectsAdditionalActivityFromDependencyManifest() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':extra'\n"
        write(root, 'extra/build.gradle', "plugins { id 'com.android.library' }; android { namespace 'example.extra'; compileSdk 36; defaultConfig { minSdk 28 } }")
        write(root, 'extra/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="example.Other" /></application></manifest>')
        new File(root, 'app/build.gradle') << "\ndependencies { implementation project(':extra') }\n"
        assertTrue(run(root, ':app:assembleVoidAndroidDebug').buildAndFail().output.contains('exactly one user Activity'))
    }

    @Test void rejectsOrdinaryCustomApplication() {
        File root = fixture()
        write(root, 'app/src/main/java/example/MyApplication.java', 'package example; public class MyApplication extends android.app.Application {}')
        assertTrue(run(root, ':app:assembleVoidAndroidDebug').buildAndFail().output.contains('must directly extend VoidAndroidApplication'))
    }

    @Test void supportsDefaultApplicationWithoutCustomInitializer() {
        File root = fixture()
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('android:name="example.MyApplication"', '')
        run(root, ':app:assembleVoidAndroidDebug').build()
        assertFalse(new File(root, 'app/build/intermediates/merged_manifest/voidAndroidDebug/prepareVoidAndroidDebugVoidManifest/AndroidManifest.xml').text.contains('void.application'))
    }

    private File fixture() {
        File root = temporary.newFolder()
        File repo = new File(System.getProperty('void.repo'))
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
            include ':app', ':void-api', ':void-runtime', ':logic'
        """)
        ['void-api', 'void-runtime'].each { module ->
            write(root, "${module}/build.gradle", new File(repo, "${module}/build.gradle").text)
            File source = new File(repo, "${module}/src/main")
            source.eachFileRecurse { file ->
                if (file.isFile()) write(root, "${module}/src/main/" + source.toPath().relativize(file.toPath()), file.text)
            }
        }
        write(root, 'app/build.gradle', """
            plugins { id 'com.lelloman.void' }
            android { namespace 'example'; compileSdk 36; defaultConfig { applicationId 'example.fixture'; minSdk 28; targetSdk 36; versionCode 1; versionName '1.0' } }
            dependencies { implementation project(':void-runtime'); implementation project(':logic') }
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
            public class MyApplication extends com.lelloman.voidandroid.runtime.VoidAndroidApplication {
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
            tasks.toList() + ['--stacktrace', '--max-workers=2', '--gradle-user-home', System.getProperty('void.gradleUserHome')])
    }
    private static void write(File root, String path, String content) {
        File file = new File(root, path)
        file.parentFile.mkdirs()
        file.text = content
    }
}
