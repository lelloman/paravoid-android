package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class ApplicationManifestTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void preservesReceiverAndServiceDeclarationsAndAttributes() {
        def task = fixture('''
            <receiver android:name="example.Receiver" android:exported="false" android:permission="example.PRIVATE" />
            <service android:name="example.Service" android:exported="false" android:stopWithTask="true" />
        ''')
        task.rewrite()
        String manifest = task.outputManifest.get().asFile.text
        assertTrue(manifest.contains('example.Receiver'))
        assertTrue(manifest.contains('example.PRIVATE'))
        assertTrue(manifest.contains('example.Service'))
        assertTrue(manifest.contains('android:stopWithTask="true"'))
    }

    @Test void stillRejectsAdditionalActivities() {
        def task = fixture('<activity android:name="example.Second" />')
        assertTrue(assertThrows(GradleException, { task.rewrite() }).message.contains('exactly one user Activity'))
    }

    @Test void preservesProviderAndAndroidXFactoryForDelegation() {
        def task = fixture('<provider android:name="example.Provider" android:authorities="example.data" android:exported="false" android:initOrder="50" />',
            'android:appComponentFactory="androidx.core.app.CoreComponentFactory"')
        task.rewrite()
        String manifest = task.outputManifest.get().asFile.text
        assertTrue(manifest.contains('example.Provider'))
        assertTrue(manifest.contains('android:authorities="example.data"'))
        assertTrue(manifest.contains('android:initOrder="50"'))
        assertTrue(manifest.contains('android:name="paravoid.componentFactory"'))
        assertTrue(manifest.contains('android:value="androidx.core.app.CoreComponentFactory"'))
        assertTrue(manifest.contains('android:appComponentFactory="com.lelloman.paravoidandroid.runtime.ParavoidComponentFactory"'))
    }

    @Test void rejectsCustomFactoryHooksRatherThanSilentlyIgnoringThem() {
        def task = fixture('', 'android:appComponentFactory="example.CustomFactory"')
        assertTrue(assertThrows(GradleException, { task.rewrite() }).message.contains('custom Application/classloader factory hooks'))
    }

    private ApplicationManifestTask fixture(String components, String applicationAttributes = '') {
        File root = temporary.newFolder()
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        def task = project.tasks.create('rewrite', ApplicationManifestTask)
        File manifest = new File(root, 'AndroidManifest.xml')
        manifest.text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example">
                <application ${applicationAttributes}>
                    <activity android:name=".Main" android:exported="true">
                        <intent-filter><action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
                    </activity>
                    ${components}
                </application>
            </manifest>
        """
        task.inputManifest.set(manifest)
        task.outputManifest.set(new File(root, 'out.xml'))
        task.payloadMetadata.set(new File(root, 'payload.properties'))
        return task
    }
}
