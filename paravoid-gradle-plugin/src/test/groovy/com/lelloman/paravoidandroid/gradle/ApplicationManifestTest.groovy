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

    @Test void qualifiesAllServiceNamesInPayloadMetadata() {
        def task = fixture('''<service android:name=".First" /><service android:name="Second" />
            <service android:name="dependency.Third" />''')
        task.rewrite()
        Properties info = new Properties()
        task.payloadMetadata.get().asFile.withInputStream { info.load(it) }
        assertEquals('example.First;example.Second;dependency.Third', info.getProperty('services'))
    }

    @Test void recordsEmptyServicesForActivityOnlyApps() {
        def task = fixture('')
        task.rewrite()
        Properties info = new Properties()
        task.payloadMetadata.get().asFile.withInputStream { info.load(it) }
        assertEquals('', info.getProperty('services'))
    }

    @Test void preservesLibraryActivitiesAndTheirInstalledContract() {
        def task = fixture('''
            <activity android:name="net.openid.appauth.AuthorizationManagementActivity" android:exported="false"
                android:launchMode="singleTask" android:theme="@style/AuthTheme" />
            <activity android:name="com.lelloman.androidoscopy.ui.DashboardActivity" android:exported="false" />
            <activity android:name="com.lelloman.androidoscopy.ui.SessionActivity" android:exported="true"
                android:permission="example.PRIVATE">
                <meta-data android:name="fixture" android:value="preserved" />
                <intent-filter><action android:name="android.intent.action.VIEW" />
                    <category android:name="android.intent.category.DEFAULT" /><data android:scheme="fixture" /></intent-filter>
            </activity>
        ''')
        task.rewrite()
        Properties info = new Properties()
        task.payloadMetadata.get().asFile.withInputStream { info.load(it) }
        assertEquals('example.Main', info.getProperty('activity'))
        assertEquals('example.Main;net.openid.appauth.AuthorizationManagementActivity;com.lelloman.androidoscopy.ui.DashboardActivity;com.lelloman.androidoscopy.ui.SessionActivity', info.getProperty('activities'))
        String output = task.outputManifest.get().asFile.text
        ['android:launchMode="singleTask"', 'android:theme="@style/AuthTheme"',
         'android:permission="example.PRIVATE"', 'android:value="preserved"', 'android:scheme="fixture"'].each {
            assertTrue(output.contains(it))
        }
    }

    @Test void selectsLauncherRatherThanFirstActivityInMergedManifest() {
        def task = fixture('<activity android:name=".Before" />')
        task.inputManifest.get().asFile.text = task.inputManifest.get().asFile.text.replace(
            '<activity android:name=".Main"', '<activity android:name="dependency.First" /><activity android:name=".Main"')
        task.rewrite()
        assertTrue(task.payloadMetadata.get().asFile.text.contains('activity=example.Main\n'))
        assertTrue(task.payloadMetadata.get().asFile.text.contains('activities=dependency.First;example.Main;example.Before\n'))
    }

    @Test void stillRejectsMultipleLaunchersAndAliases() {
        def extra = fixture('''<activity android:name="example.Second" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity>''')
        assertTrue(assertThrows(GradleException, { extra.rewrite() }).message.contains('exactly one MAIN/LAUNCHER'))
        def alias = fixture('<activity-alias android:name="example.Alias" android:targetActivity="example.Main" />')
        assertTrue(assertThrows(GradleException, { alias.rewrite() }).message.contains('Activity aliases'))
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
