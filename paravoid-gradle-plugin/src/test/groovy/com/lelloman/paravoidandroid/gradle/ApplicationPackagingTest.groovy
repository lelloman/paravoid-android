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

    @Test void gatesEmbeddedShellAgainstReviewedContractWithoutFreezingPayload() {
        File root = fixture()
        File build = new File(root, 'app/build.gradle')
        build.text = build.text.replace('minSdk 28', 'minSdk 30')
        String task = ':app:packageParavoidAndroidDebugParavoidResourceShell'
        String export = ':app:exportParavoidAndroidDebugParavoidBaseline'
        write(root, 'app/src/main/res/values/strings.xml', '<resources><string name="movable">A</string></resources>')
        run(root, export).build()
        File output = new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug')
        File baseline = new File(root, 'app/paravoid/baseline/paravoidAndroidDebug')
        baseline.mkdirs()
        ['resource-ledger.json', 'resource-boundary.json', 'shell-contract.json'].each { name ->
            new File(baseline, name).bytes = new File(output, 'baseline-candidate/' + name).bytes
        }
        byte[] accepted = new File(baseline, 'shell-contract.json').bytes
        build << "\nparavoid { baselineDirectory = layout.projectDirectory.dir('paravoid/baseline') }\n"
        run(root, task).build()
        new ZipFile(new File(output, 'resource-shell.apk')).withCloseable {
            assertArrayEquals(accepted, ResourceArchive.read(it, 'assets/paravoid/shell-contract.json'))
        }
        write(root, 'app/src/main/res/values/strings.xml', '<resources><string name="movable">B</string><string name="new_resource">New</string></resources>')
        write(root, 'app/src/main/resources/content.txt', 'changed payload resource')
        File activity = new File(root, 'app/src/main/java/example/MainActivity.java')
        activity.text = activity.text.replace('int count', 'int extra = 7; int count')
        build << '\nandroid.defaultConfig.versionCode = 2\n'
        run(root, task).build()
        assertArrayEquals(accepted, new File(output, 'baseline-candidate/shell-contract.json').bytes)
        new File(baseline, 'resource-ledger.json').bytes = new File(output, 'baseline-candidate/resource-ledger.json').bytes
        run(root, task).build()
        assertArrayEquals(accepted, new File(output, 'baseline-candidate/shell-contract.json').bytes)
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('<application ', '<uses-permission android:name="android.permission.CAMERA"/><application ')
        // Dedicated gate reports the declaration; packaging also depends on this gate.
        assertTrue(run(root, ':app:checkParavoidAndroidDebugParavoidContract').buildAndFail().output.contains('android.permission.CAMERA'))
        assertArrayEquals(accepted, new File(baseline, 'shell-contract.json').bytes)
    }

    @Test void packagesSignedEmbeddedResourceShellWithoutChangingNormalOrDexOnlyApks() {
        File root = fixture()
        String task = ':app:packageParavoidAndroidDebugParavoidResourceShell'
        assertTrue(run(root, task).buildAndFail().output.contains('requires minSdk >= 30'))
        File build = new File(root, 'app/build.gradle')
        build.text = build.text.replace('minSdk 28', 'minSdk 30')
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('<application ', '<application android:label="@string/pinned" ')
        write(root, 'app/src/main/res/values/strings.xml', '<resources><string name="pinned">Shell</string><string name="movable">Payload</string></resources>')
        write(root, 'app/src/main/assets/content.txt', 'Movable asset')
        write(root, 'app/src/main/resources/probe/merged.txt', 'app\n')
        write(root, 'logic/src/main/resources/probe/merged.txt', 'library\n')
        write(root, 'app/src/main/resources/probe/excluded.txt', 'excluded')
        build << "\nandroid.packaging.resources { merges += 'probe/merged.txt'; excludes += 'probe/excluded.txt' }\n"
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug').build()
        File normal = new File(root, 'app/build/outputs/apk/normal/debug/app-normal-debug.apk')
        File dexOnly = new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')
        byte[] normalBefore = normal.bytes, dexBefore = dexOnly.bytes
        run(root, task).build()
        assertArrayEquals(normalBefore, normal.bytes)
        assertArrayEquals(dexBefore, dexOnly.bytes)
        File shell = new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/resource-shell.apk')
        assertTrue(new com.android.apksig.ApkVerifier.Builder(shell).build().verify().verified)
        String resources = dumpResources(root, shell)
        assertTrue(resources.contains('string/pinned'))
        assertFalse(resources.contains('string/movable'))
        new ZipFile(shell).withCloseable { zip ->
            assertNull(zip.getEntry('assets/content.txt'))
            assertNotNull(zip.getEntry('assets/paravoid/module.zip'))
            byte[] payload = ResourceArchive.read(zip, 'assets/paravoid/resources.apk')
            assertEquals(java.security.MessageDigest.getInstance('SHA-256').digest(payload).encodeHex().toString() + '\n',
                new String(ResourceArchive.read(zip, 'assets/paravoid/resources.sha256')))
            assertTrue(dexText(zip).contains('EmbeddedResources'))
            assertNull(zip.getEntry('probe/merged.txt'))
            byte[] javaResources = ResourceArchive.read(zip, 'assets/paravoid/java-resources.jar')
            assertNotNull(javaResources)
            Map<String, byte[]> contents = [:]
            new ZipInputStream(new ByteArrayInputStream(javaResources)).withCloseable { jar ->
                ZipEntry entry
                while ((entry = jar.nextEntry) != null) contents[entry.name] = jar.readAllBytes()
            }
            assertFalse(contents.containsKey('probe/excluded.txt'))
            new ZipFile(dexOnly).withCloseable { original ->
                assertArrayEquals(ResourceArchive.read(original, 'probe/merged.txt'), contents['probe/merged.txt'])
            }
            assertEquals(java.security.MessageDigest.getInstance('SHA-256').digest(javaResources).encodeHex().toString() + '\n',
                new String(ResourceArchive.read(zip, 'assets/paravoid/java-resources.sha256')))
        }
        assertTrue(run(root, ':app:packageParavoidAndroidReleaseParavoidResourceShell').buildAndFail().output.contains('requires the variant signingConfig'))
        manifest.text = manifest.text.replace('<application ', '<application android:directBootAware="true" ')
        assertTrue(run(root, task).buildAndFail().output.contains('do not yet support directBootAware or isolatedProcess'))
    }

    @Test void analyzesAndChecksPinnedManifestLibraryXmlAndAllConfigurations() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':resource-library'\n"
        write(root, 'resource-library/build.gradle', """
            plugins { id 'com.android.library' }
            android { namespace 'example.resources'; compileSdk 36; defaultConfig { minSdk 28 } }
        """)
        write(root, 'resource-library/src/main/AndroidManifest.xml', '''
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
                <meta-data android:name="library-config" android:resource="@xml/library_config" />
            </application></manifest>''')
        write(root, 'resource-library/src/main/res/xml/library_config.xml', '<config label="@string/library_label" />')
        write(root, 'resource-library/src/main/res/values/strings.xml', '<resources><string name="library_label">Library</string></resources>')
        new File(root, 'app/build.gradle') << """
            dependencies { implementation project(':resource-library') }
            paravoid { pinnedResources = ['layout/widget', 'raw/external'] }
        """
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('<application ', '<application android:label="@string/app_label" android:theme="@style/AppTheme" ')
        String values = '''<resources>
            <string name="app_label">App</string><string name="widget_label">Widget</string>
            <string name="movable">A</string><attr name="badge" format="string" />
            <dimen name="payload_spacing">12dp</dimen>
            <color name="background">#112233</color><color name="night_background">#000000</color>
            <style name="BaseTheme" parent="android:style/Theme.Material"><item name="android:windowBackground">@drawable/background</item></style>
            <style name="AppTheme" parent="BaseTheme" />
        </resources>'''
        write(root, 'app/src/main/res/values/values.xml', values)
        write(root, 'app/src/main/res/values-night/colors.xml', '<resources><color name="background">@color/night_background</color></resources>')
        write(root, 'app/src/main/res/drawable/background.xml', '<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="@color/background" /></shape>')
        write(root, 'app/src/main/res/layout/widget.xml', '''<TextView xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="wrap_content" android:layout_height="wrap_content" app:badge="@string/widget_label" />''')
        write(root, 'app/src/main/res/raw/external.txt', 'External bytes A')
        write(root, 'app/src/main/res/xml/payload_only.xml', '<config value="@string/movable" />')
        write(root, 'app/src/main/assets/data/hello.txt', 'App asset')
        write(root, 'resource-library/src/main/assets/library.txt', 'Library asset')
        String analyze = ':app:analyzeParavoidAndroidDebugParavoidResources'
        String check = ':app:checkParavoidAndroidDebugParavoidResourceBoundary'
        String split = ':app:splitParavoidAndroidDebugParavoidResources'
        String outputs = 'app/build/outputs/paravoid/paravoidAndroidDebug/'
        def parser = new groovy.json.JsonSlurper()
        run(root, ':app:assembleNormalDebug', ':app:exportParavoidAndroidDebugParavoidResourceLedger', analyze).build()
        String snapshot = new File(root, outputs + 'baseline-candidate/resource-boundary.json').text
        def report = parser.parseText(snapshot)
        ['style/AppTheme', 'style/BaseTheme', 'drawable/background', 'color/background', 'color/night_background',
         'string/app_label', 'layout/widget', 'attr/badge', 'string/widget_label', 'xml/library_config', 'string/library_label', 'raw/external'].each { name ->
            assertTrue("Not pinned: ${name}", report.pinned*.name.contains(name))
        }
        assertTrue(report.movable.contains('string/movable'))
        assertEquals(2, report.pinned.find { it.name == 'color/background' }.configurations)
        assertEquals(['xml/library_config', 'string/library_label'], report.pinned.find { it.name == 'string/library_label' }.chain)
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, analyze).build().task(analyze).outcome)
        File installed = new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')
        byte[] installedBefore = installed.bytes
        run(root, split).build()
        assertArrayEquals(installedBefore, installed.bytes)
        File shellResources = new File(root, outputs + 'resources/shell-resources.apk')
        File payloadResources = new File(root, outputs + 'resources/payload-resources.apk')
        byte[] shellA = shellResources.bytes, payloadA = payloadResources.bytes
        def shellIds = ResourceLedger.fromDump('example.fixture.paravoid', dumpResources(root, shellResources))
        def payloadIds = ResourceLedger.fromDump('example.fixture.paravoid', dumpResources(root, payloadResources))
        assertEquals(report.pinned*.name.sort(), shellIds.entries*.name.sort())
        assertEquals((report.pinned*.name + report.movable).sort(), payloadIds.entries*.name.sort())
        shellIds.entries.each { entry -> assertEquals(entry.id, payloadIds.entries.find { it.name == entry.name }.id) }
        new ZipFile(shellResources).withCloseable { shell ->
            new ZipFile(payloadResources).withCloseable { payload ->
                new ZipFile(installed).withCloseable { original ->
                    shell.entries().each { entry ->
                        assertTrue(entry.name in ['AndroidManifest.xml', 'resources.arsc'] || entry.name.startsWith('res/'))
                        if (entry.name != 'resources.arsc') assertArrayEquals(ResourceArchive.read(original, entry.name), ResourceArchive.read(shell, entry.name))
                    }
                    payload.entries().each { entry ->
                        assertArrayEquals(ResourceArchive.read(original, entry.name), ResourceArchive.read(payload, entry.name))
                    }
                }
                assertNull(shell.getEntry('res/xml/payload_only.xml'))
                assertNotNull(payload.getEntry('res/xml/payload_only.xml'))
                ['assets/data/hello.txt', 'assets/library.txt'].each { name ->
                    assertNull(shell.getEntry(name))
                    assertNotNull(payload.getEntry(name))
                }
                assertNull(payload.getEntry('classes.dex'))
                assertNull(payload.getEntry('assets/paravoid/module.zip'))
                assertFalse(payload.entries().any { it.name.startsWith('META-INF/') || it.name.startsWith('lib/') })
            }
        }
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, split).build().task(split).outcome)
        assertTrue(run(root, check).buildAndFail().output.contains('Configure paravoid.baselineDirectory'))
        ['resource-ledger.json', 'resource-boundary.json'].each { name ->
            write(root, 'app/paravoid/baseline/paravoidAndroidDebug/' + name, new File(root, outputs + 'baseline-candidate/' + name).text)
        }
        new File(root, 'app/build.gradle') << "\nparavoid { baselineDirectory = layout.projectDirectory.dir('paravoid/baseline') }\n"
        run(root, check).build()
        write(root, 'app/src/main/res/values/values.xml', values.replace('>A<', '>B<'))
        run(root, check, split).build() // Movable values are not frozen.
        assertArrayEquals(shellA, shellResources.bytes)
        assertFalse(Arrays.equals(payloadA, payloadResources.bytes))
        byte[] payloadB = payloadResources.bytes
        run(root, ':app:clean', check, split).build()
        assertArrayEquals(shellA, shellResources.bytes)
        assertArrayEquals(payloadB, payloadResources.bytes)
        assertEquals(snapshot, new File(root, outputs + 'baseline-candidate/resource-boundary.json').text)
        write(root, 'app/src/main/res/values/values.xml', values.replace('#000000', '#ffffff'))
        assertTrue(run(root, check).buildAndFail().output.contains('Pinned resource changed: color/night_background'))
        assertTrue(run(root, split).buildAndFail().output.contains('Pinned resource changed: color/night_background'))
        write(root, 'app/src/main/res/values/values.xml', values)
        write(root, 'app/src/main/res/raw/external.txt', 'External bytes B')
        assertTrue(run(root, check).buildAndFail().output.contains('Pinned resource changed: raw/external'))
        write(root, 'app/src/main/res/raw/external.txt', 'External bytes A')
        manifest.text = manifest.text.replace('android:exported="true"', 'android:exported="false"')
        assertTrue(run(root, check).buildAndFail().output.contains('Installed manifest changed'))
        assertEquals(snapshot, new File(root, 'app/paravoid/baseline/paravoidAndroidDebug/resource-boundary.json').text)
        new File(root, 'app/build.gradle') << "\nparavoid.pinnedResources.add('string/does_not_exist')\n"
        assertTrue(run(root, analyze).buildAndFail().output.contains('Unknown explicit pinned resource'))
        assertNull(run(root, ':app:assembleNormalDebug').build().task(analyze))
    }

    @Test void exportsAndReusesExactResourceLedgerAcrossAppLibraryAndGeneratedResources() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':resource-library'\n"
        write(root, 'resource-library/build.gradle', """
            plugins { id 'com.android.library' }
            android { namespace 'example.resources'; compileSdk 36; defaultConfig { minSdk 28 } }
        """)
        write(root, 'resource-library/src/main/res/values/values.xml', '''<resources>
            <string name="library_title">Library</string>
            <declare-styleable name="LibraryView"><attr name="libraryLabel" format="string" /></declare-styleable>
        </resources>''')
        write(root, 'resource-library/src/main/java/example/resources/Library.java', '''package example.resources;
            public class Library { public static int[] attrs() { return R.styleable.LibraryView; }
                public static int title() { return R.string.library_title; } }
        ''')
        new File(root, 'app/build.gradle') << '''
            dependencies { implementation project(':resource-library') }
            abstract class GeneratedResources extends DefaultTask {
                @OutputDirectory abstract DirectoryProperty getOutputDirectory()
                @TaskAction void generate() {
                    def output = outputDirectory.file('values/generated.xml').get().asFile
                    output.parentFile.mkdirs()
                    output.text = '<resources><string name="generated_title">Generated</string></resources>'
                }
            }
            def generated = tasks.register('generatedResources', GeneratedResources) {
                outputDirectory.set(layout.buildDirectory.dir('generated/probe/res'))
            }
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                variant.sources.res.addGeneratedSourceDirectory(generated) { it.outputDirectory }
            }
        '''
        String resourcesA = '''<resources><string name="title">A</string>
            <string name="removed">Only A</string><style name="Theme.App" parent="android:style/Theme.Material" />
        </resources>'''
        write(root, 'app/src/main/res/values/values.xml', resourcesA)
        write(root, 'app/src/main/java/example/ResourceProbe.java', '''package example;
            public class ResourceProbe { int title = R.string.title; int generated = R.string.generated_title;
                int[] attrs = example.resources.Library.attrs(); int theme = R.style.Theme_App; }
        ''')
        String task = ':app:exportParavoidAndroidDebugParavoidResourceLedger'
        String split = ':app:splitParavoidAndroidDebugParavoidResources'
        String analyze = ':app:analyzeParavoidAndroidDebugParavoidResources'
        String candidatePath = 'app/build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/resource-ledger.json'
        run(root, ':app:assembleNormalDebug', task, analyze, split).build()
        File payloadResources = new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/resources/payload-resources.apk')
        assertTrue(dumpResources(root, payloadResources).contains('string/removed'))
        def a = ResourceLedger.read(new File(root, candidatePath).text)
        assertEquals('example.fixture.paravoid', a.applicationId)
        ['string/title', 'string/library_title', 'string/generated_title', 'attr/libraryLabel', 'style/Theme.App'].each { name ->
            assertNotNull("Missing ${name}", a.entries.find { it.name == name })
        }
        assertFalse(a.entries.any { it.name.startsWith('styleable/') })
        String baselinePath = 'app/paravoid/baseline/paravoidAndroidDebug/resource-ledger.json'
        write(root, baselinePath, a.toJson())
        write(root, 'app/paravoid/baseline/paravoidAndroidDebug/resource-boundary.json', new File(root,
            'app/build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/resource-boundary.json').text)
        new File(root, 'app/build.gradle') << "\nparavoid { baselineDirectory = layout.projectDirectory.dir('paravoid/baseline') }\n"
        String resourcesB = resourcesA.replace('<string name="removed">Only A</string>', '<string name="a_added">Only B</string>').replace('>A<', '>B<')
        write(root, 'app/src/main/res/values/values.xml', resourcesB)
        def updated = run(root, ':app:assembleNormalDebug', task, split).build()
        String payloadDump = dumpResources(root, payloadResources)
        assertFalse(payloadDump.contains('string/removed'))
        assertTrue(payloadDump.contains('string/a_added'))
        assertNotNull(updated.task(':app:prepareParavoidAndroidDebugParavoidResourceIds'))
        assertNull(updated.task(':app:prepareNormalDebugParavoidResourceIds'))
        def b = ResourceLedger.read(new File(root, candidatePath).text)
        a.entries.each { previous -> assertEquals(previous.id, b.entries.find { it.name == previous.name }.id) }
        assertTrue(b.entries.find { it.name == 'string/removed' }.removed)
        assertFalse(a.entries*.id.contains(b.entries.find { it.name == 'string/a_added' }.id))
        assertEquals(a.toJson(), new File(root, baselinePath).text)
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, task).build().task(task).outcome)
        // Another release branch reserved the next ID. Changing only the baseline
        // must invalidate linking even though the --stable-ids option is a string.
        String originallyAddedId = b.entries.find { it.name == 'string/a_added' }.id
        def reconciled = new ResourceLedger(a.applicationId, a.entries +
            [[name: 'string/branch_reservation', id: originallyAddedId, removed: true]])
        write(root, baselinePath, reconciled.toJson())
        def baselineOnly = run(root, task).build()
        assertEquals(TaskOutcome.SUCCESS, baselineOnly.task(':app:processParavoidAndroidDebugResources').outcome)
        b = ResourceLedger.read(new File(root, candidatePath).text)
        assertNotEquals(originallyAddedId, b.entries.find { it.name == 'string/a_added' }.id)
        assertTrue(b.entries.find { it.name == 'string/branch_reservation' }.removed)
        assertEquals(reconciled.toJson(), new File(root, baselinePath).text)
        run(root, ':app:clean', task).build()
        assertEquals(b.toJson(), new File(root, candidatePath).text)
        // Promote B explicitly, then restore A. B-only IDs must remain reserved.
        write(root, baselinePath, b.toJson())
        write(root, 'app/src/main/res/values/values.xml', resourcesA)
        run(root, task).build()
        def restored = ResourceLedger.read(new File(root, candidatePath).text)
        assertTrue(restored.entries.find { it.name == 'string/a_added' }.removed)
        assertFalse(restored.entries.find { it.name == 'string/removed' }.removed)
        assertEquals(b.toJson(), new File(root, baselinePath).text)
    }

    @Test void resourceSplitRejectsUnknownReservedAssets() {
        File root = fixture()
        write(root, 'app/src/main/assets/paravoid/unowned.txt', 'Must not silently disappear')
        assertTrue(run(root, ':app:splitParavoidAndroidDebugParavoidResources').buildAndFail().output
            .contains('Unknown reserved Paravoid assets'))
    }

    @Test void rejectsWrongResourceBaselineWithoutAffectingNormalBuild() {
        File root = fixture()
        write(root, 'app/paravoid/baseline/paravoidAndroidDebug/resource-ledger.json',
            new ResourceLedger('another.app', []).toJson())
        new File(root, 'app/build.gradle') << "\nparavoid { baselineDirectory = layout.projectDirectory.dir('paravoid/baseline') }\n"
        def normal = run(root, ':app:assembleNormalDebug').build()
        assertNull(normal.task(':app:prepareParavoidAndroidDebugParavoidResourceIds'))
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output.contains('baseline applicationId mismatch'))
    }

    @Test void exportsResourceFreeAppAndReservesItsEntireRemovedTable() {
        File root = fixture()
        String task = ':app:exportParavoidAndroidDebugParavoidResourceLedger'
        String output = 'app/build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/resource-ledger.json'
        run(root, task, ':app:analyzeParavoidAndroidDebugParavoidResources', ':app:splitParavoidAndroidDebugParavoidResources').build()
        assertTrue(ResourceLedger.read(new File(root, output).text).entries.empty)
        def boundary = new groovy.json.JsonSlurper().parse(new File(root,
            'app/build/outputs/paravoid/paravoidAndroidDebug/baseline-candidate/resource-boundary.json'))
        assertTrue(boundary.pinned.empty)
        assertTrue(boundary.movable.empty)
        write(root, 'app/paravoid/baseline/paravoidAndroidDebug/resource-ledger.json',
            new ResourceLedger('example.fixture.paravoid', [[name: 'string/old', id: '0x7f010000', removed: false]]).toJson())
        new File(root, 'app/build.gradle') << "\nparavoid { baselineDirectory = layout.projectDirectory.dir('paravoid/baseline') }\n"
        run(root, task).build()
        def retired = ResourceLedger.read(new File(root, output).text)
        assertEquals([[name: 'string/old', id: '0x7f010000', removed: true]], retired.entries)
    }

    @Test void producesTwoModesAndTransformsOnlyPayloadApplication() {
        File root = fixture()
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug', ':app:bundleNormalRelease', ':app:bundleParavoidAndroidRelease').build()
        File normal = new File(root, 'app/build/outputs/apk/normal/debug/app-normal-debug.apk')
        File shell = new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')
        assertEquals('example.fixture', apkApplicationId(normal))
        assertEquals('example.fixture.paravoid', apkApplicationId(shell))
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
        assertTrue(manifest.contains('package="example.fixture.paravoid"'))
        def second = run(root, ':app:assembleParavoidAndroidDebug').build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':app:packageParavoidAndroidDebugParavoidApplication').outcome)
    }

    @Test void configuresShellIdentityAndComposesWithOtherVariantSuffixes() {
        File root = fixture()
        new File(root, 'app/build.gradle') << '''
            android {
                flavorDimensions.add(0, 'audience')
                productFlavors {
                    phone { dimension 'audience'; applicationIdSuffix '.phone' }
                    paravoidAndroid { applicationIdSuffix '.sandbox' }
                }
                buildTypes.debug.applicationIdSuffix = '.debug'
            }
        '''
        File manifest = new File(root, 'app/src/main/AndroidManifest.xml')
        manifest.text = manifest.text.replace('<application', '<permission android:name="${applicationId}.PRIVATE" /><application')
        run(root, ':app:assemblePhoneNormalDebug', ':app:assemblePhoneParavoidAndroidDebug').build()
        File normal = new File(root, 'app/build/outputs/apk/phoneNormal/debug/app-phone-normal-debug.apk')
        File shell = new File(root, 'app/build/outputs/apk/phoneParavoidAndroid/debug/app-phone-paravoidAndroid-debug.apk')
        assertEquals('example.fixture.phone.debug', apkApplicationId(normal))
        assertEquals('example.fixture.phone.sandbox.debug', apkApplicationId(shell))
        String merged = new File(root, 'app/build/intermediates/merged_manifest/phoneParavoidAndroidDebug/preparePhoneParavoidAndroidDebugParavoidManifest/AndroidManifest.xml').text
        assertTrue(merged.contains('example.fixture.phone.sandbox.debug.PRIVATE'))
        assertTrue(merged.contains('example.MainActivity'))

        new File(root, 'app/build.gradle') << "\nandroid.productFlavors.paravoidAndroid.applicationIdSuffix = ''\n"
        run(root, ':app:assemblePhoneParavoidAndroidDebug').build()
        assertEquals('example.fixture.phone.debug', apkApplicationId(shell))

        new File(root, 'app/build.gradle') << "\nandroid.productFlavors.paravoidAndroid.applicationId = 'custom.shell'\n"
        run(root, ':app:assemblePhoneNormalDebug', ':app:assemblePhoneParavoidAndroidDebug').build()
        assertEquals('custom.shell.phone.debug', apkApplicationId(shell))
        assertEquals('example.fixture.phone.debug', apkApplicationId(normal))
    }

    @Test void normalApi24ApplicationDoesNotNeedShellRuntime() {
        File root = fixture()
        File build = new File(root, 'app/build.gradle')
        build.text = build.text.replace('minSdk 28', 'minSdk 24')
            .replace("implementation project(':paravoid-runtime')",
                "implementation project(':paravoid-api'); paravoidAndroidImplementation project(':paravoid-runtime')")
        build << '\nandroid.productFlavors.paravoidAndroid.minSdk = 28\n'
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug').build()
        new ZipFile(new File(root, 'app/build/outputs/apk/normal/debug/app-normal-debug.apk')).withCloseable { apk ->
            assertTrue(dexText(apk).contains('Lcom/lelloman/paravoidandroid/runtime/ParavoidAndroidApplication;'))
            assertFalse(dexText(apk).contains('Lcom/lelloman/paravoidandroid/runtime/ShellApplication;'))
        }
    }

    @Test void packagesAdditionalActivityFromDependencyManifestAndAppliesHooks() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':extra'\n"
        write(root, 'extra/build.gradle', "plugins { id 'com.android.library' }; android { namespace 'example.extra'; compileSdk 36; defaultConfig { minSdk 28 } }")
        write(root, 'extra/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="example.Other" /></application></manifest>')
        write(root, 'extra/src/main/java/example/Other.java', 'package example; public class Other extends android.app.Activity {}')
        new File(root, 'app/build.gradle') << "\ndependencies { implementation project(':extra') }\n"
        run(root, ':app:assembleNormalDebug', ':app:assembleParavoidAndroidDebug').build()
        new ZipFile(new File(root, 'app/build/outputs/apk/paravoidAndroid/debug/app-paravoidAndroid-debug.apk')).withCloseable { apk ->
            assertFalse(dexText(apk).contains('Lexample/Other;'))
        }
        new ZipFile(new File(root, 'app/build/outputs/paravoid/paravoidAndroidDebug/module.zip')).withCloseable { zip ->
            assertTrue(dexText(zip).contains('Lexample/Other;'))
        }
        new ZipFile(new File(root, 'app/build/tmp/packageParavoidAndroidDebugParavoidApplication/payload.jar')).withCloseable { zip ->
            String bytes = new String(zip.getInputStream(zip.getEntry('example/Other.class')).readAllBytes(), 'ISO-8859-1')
            ['getClassLoader', 'createConfigurationContext', 'onCreate', 'onSaveInstanceState', 'prepare', 'protect'].each {
                assertTrue(it, bytes.contains(it))
            }
        }
        assertTrue(new File(root, 'app/build/intermediates/merged_manifest/paravoidAndroidDebug/prepareParavoidAndroidDebugParavoidManifest/AndroidManifest.xml').text.contains('example.Other'))
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

    @Test void rejectsNativeApi28OnlyForShellAndTracksAddedLibraries() {
        File root = fixture()
        run(root, ':app:assembleParavoidAndroidDebug').build()
        // Introducing JNI after a successful code-only build must invalidate validation.
        write(root, 'app/src/main/jniLibs/x86_64/libfixture.so', 'packaging-only native fixture')
        run(root, ':app:assembleNormalDebug', ':app:bundleNormalRelease').build()
        [':app:assembleParavoidAndroidDebug', ':app:bundleParavoidAndroidRelease'].each { task ->
            String output = run(root, task).buildAndFail().output
            assertTrue(output, output.contains('native libraries require minSdk >= 29'))
            assertTrue(output, output.contains('libfixture.so'))
            assertTrue(output, output.contains('paravoidAndroid flavor'))
        }
        new File(root, 'app/build.gradle') << '\nandroid.productFlavors.paravoidAndroid.minSdk = 29\n'
        run(root, ':app:assembleParavoidAndroidDebug', ':app:bundleParavoidAndroidRelease').build()
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, ':app:assembleParavoidAndroidDebug').build()
            .task(':app:validateParavoidAndroidDebugParavoidNativeLibraries').outcome)
    }

    @Test void detectsTransitiveAarNativeLibrariesAndRespectsExclusions() {
        File root = fixture()
        new File(root, 'settings.gradle') << "\ninclude ':nativeleaf', ':wrapper'\n"
        ['nativeleaf', 'wrapper'].each { name ->
            write(root, "${name}/build.gradle", "plugins { id 'com.android.library' }; android { namespace 'example.${name}'; compileSdk 36; defaultConfig { minSdk 28 } }")
            write(root, "${name}/src/main/AndroidManifest.xml", '<manifest />')
        }
        write(root, 'nativeleaf/src/main/jniLibs/x86_64/libtransitive.so', 'packaging-only native fixture')
        new File(root, 'wrapper/build.gradle') << "\ndependencies { api project(':nativeleaf') }\n"
        new File(root, 'app/build.gradle') << "\ndependencies { implementation project(':wrapper') }\n"
        String output = run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output
        assertTrue(output, output.contains('native libraries require minSdk >= 29'))
        assertTrue(output, output.contains('libtransitive.so'))
        new File(root, 'app/build.gradle') << "\nandroid.packaging.jniLibs.excludes.add('**/libtransitive.so')\n"
        run(root, ':app:assembleParavoidAndroidDebug').build()
        new File(root, 'app/build.gradle') << "\nandroid.packaging.jniLibs.excludes.clear(); android.defaultConfig.ndk.abiFilters.add('arm64-v8a')\n"
        // MERGED_NATIVE_LIBS precedes final ABI filtering; deliberately conservative.
        assertTrue(run(root, ':app:assembleParavoidAndroidDebug').buildAndFail().output
            .contains('ndk.abiFilters alone does not bypass it'))
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

    private static String apkApplicationId(File apk) {
        assertTrue(apk.isFile())
        new groovy.json.JsonSlurper().parse(new File(apk.parentFile, 'output-metadata.json')).applicationId
    }

    private static String dumpResources(File root, File apk) {
        Properties local = new Properties()
        new File(root, 'local.properties').withInputStream { local.load(it) }
        def process = new ProcessBuilder(new File(local.getProperty('sdk.dir'), 'build-tools/35.0.0/aapt2').absolutePath,
            'dump', 'resources', apk.absolutePath).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8')
        assertEquals(output, 0, process.waitFor())
        output
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
