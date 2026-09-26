package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class ApplicationManifestTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void backgroundPushAddsPrivateTypedServiceOnlyWhenEnabled() {
        def disabled=fixture(''); disabled.complete.set(true); disabled.updatesEnabled.set(true); disabled.pushEnabled.set(true); disabled.rewrite()
        assertFalse(disabled.outputManifest.get().asFile.text.contains('PushForegroundService'))
        assertFalse(disabled.outputManifest.get().asFile.text.contains('FOREGROUND_SERVICE_SPECIAL_USE'))
        def task=fixture(''); task.complete.set(true); task.updatesEnabled.set(true); task.pushEnabled.set(true)
        task.pushBackgroundConnection.set(true); task.rewrite()
        def factory=javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.namespaceAware=true
        def document=factory.newDocumentBuilder().parse(task.outputManifest.get().asFile)
        def service=document.getElementsByTagName('service').find { it.getAttributeNS(ApplicationManifestTask.ANDROID,'name').endsWith('PushForegroundService') }
        assertNotNull(service)
        assertEquals(':paravoid_updates',service.getAttributeNS(ApplicationManifestTask.ANDROID,'process'))
        assertEquals('false',service.getAttributeNS(ApplicationManifestTask.ANDROID,'exported'))
        assertEquals('specialUse',service.getAttributeNS(ApplicationManifestTask.ANDROID,'foregroundServiceType'))
        assertEquals('android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',service.getElementsByTagName('property').item(0).getAttributeNS(ApplicationManifestTask.ANDROID,'name'))
        assertTrue(task.outputManifest.get().asFile.text.contains('FOREGROUND_SERVICE_SPECIAL_USE'))
        assertTrue(task.outputManifest.get().asFile.text.contains('paravoid.backgroundPush'))
        assertFalse(ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build().objects.newInstance(ParavoidApplicationExtension).updates.push.backgroundConnection.get())
    }

    @Test void pushIsOptInAndRoutesAdapterWithoutPayloadStartup() {
        def disabled=fixture(''); disabled.complete.set(true); disabled.updatesEnabled.set(true); disabled.rewrite()
        assertFalse(disabled.outputManifest.get().asFile.text.contains('UpdatePromptActivity'))
        assertTrue(disabled.outputManifest.get().asFile.text.contains('RestartActivity'))
        def task=fixture('<service android:name="example.PushService" android:exported="false" />')
        task.complete.set(true); task.updatesEnabled.set(true); task.pushEnabled.set(true)
        task.pushComponents.set(['example.PushService']); task.rewrite()
        def factory=javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.namespaceAware=true
        def document=factory.newDocumentBuilder().parse(task.outputManifest.get().asFile)
        def service=document.getElementsByTagName('service').find { it.getAttributeNS(ApplicationManifestTask.ANDROID,'name')=='example.PushService' }
        assertEquals(':paravoid_updates',service.getAttributeNS(ApplicationManifestTask.ANDROID,'process'))
        assertTrue(task.outputManifest.get().asFile.text.contains('UpdatePromptActivity'))
        assertTrue(task.outputManifest.get().asFile.text.contains('POST_NOTIFICATIONS'))
    }
    @Test void completeProfileAddsPrivateRecoveryProcessWithoutChangingPayloadDeclarations() {
        def task = fixture('<service android:name="example.Work" android:process=":worker" />')
        task.complete.set(true)
        task.rewrite()
        String output = task.outputManifest.get().asFile.text
        assertTrue(output.contains('paravoid.complete'))
        assertTrue(output.contains('com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity'))
        assertFalse(output.contains('activity-alias'))
        assertTrue(output.contains('android:process=":paravoid_recovery"'))
        assertTrue(output.contains('android:process=":worker"'))
        assertTrue(output.contains('android.permission.ACCESS_NETWORK_STATE'))
        assertFalse(output.contains('usesCleartextTraffic'))
        assertTrue(task.payloadMetadata.get().asFile.text.contains('complete=true'))
    }

    @Test void completeProfileRejectsReservedMetadataAndProcess() {
        [ '<meta-data android:name="paravoid.complete" android:value="false" />',
          '<service android:name="example.Work" android:process=":paravoid_recovery" />'].each { source ->
            def task = fixture(source); task.complete.set(true)
            assertThrows(GradleException) { task.rewrite() }
        }
    }

    @Test void completeControlsLauncherIsPublicAliasToPrivateRecoveryOnly() {
        def task = fixture('', 'android:permission="example.PRIVATE"'); task.complete.set(true); task.controlsLauncher.set(true); task.rewrite()
        def factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.namespaceAware = true
        def document = factory.newDocumentBuilder().parse(task.outputManifest.get().asFile)
        def aliases = document.getElementsByTagName('activity-alias')
        assertEquals(1, aliases.length)
        def alias = aliases.item(0)
        assertEquals('com.lelloman.paravoidandroid.runtime.UpdatesLauncher', alias.getAttributeNS(ApplicationManifestTask.ANDROID, 'name'))
        assertEquals('com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity', alias.getAttributeNS(ApplicationManifestTask.ANDROID, 'targetActivity'))
        assertEquals('true', alias.getAttributeNS(ApplicationManifestTask.ANDROID, 'exported'))
        assertTrue(alias.hasAttributeNS(ApplicationManifestTask.ANDROID, 'permission'))
        assertEquals('', alias.getAttributeNS(ApplicationManifestTask.ANDROID, 'permission'))
        assertEquals('App updates', alias.getAttributeNS(ApplicationManifestTask.ANDROID, 'label'))
        assertEquals('android.intent.action.MAIN', alias.getElementsByTagName('action').item(0).getAttributeNS(ApplicationManifestTask.ANDROID, 'name'))
        assertEquals('android.intent.category.LAUNCHER', alias.getElementsByTagName('category').item(0).getAttributeNS(ApplicationManifestTask.ANDROID, 'name'))
        def target = document.getElementsByTagName('activity').find { it.getAttributeNS(ApplicationManifestTask.ANDROID, 'name').endsWith('ShellUpdatesActivity') }
        assertEquals('false', target.getAttributeNS(ApplicationManifestTask.ANDROID, 'exported'))
        assertEquals(':paravoid_recovery', target.getAttributeNS(ApplicationManifestTask.ANDROID, 'process'))
        assertEquals('example.Main', new Properties().with { load(task.payloadMetadata.get().asFile.newInputStream()); getProperty('activities') })
    }

    @Test void launcherCanBeDisabledAndIsNeverAddedToLegacyProfile() {
        [[true, false], [false, true], [false, false]].each { settings ->
            def task = fixture(''); task.complete.set(settings[0]); task.controlsLauncher.set(settings[1]); task.rewrite()
            assertFalse(task.outputManifest.get().asFile.text.contains('activity-alias'))
        }
        def project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        assertFalse(project.objects.newInstance(ParavoidApplicationExtension).controlsLauncher.get())
    }

    @Test void completeProfileRejectsUnsupportedComponentModesBeforeSigning() {
        ['activity', 'service', 'receiver', 'provider'].each { kind ->
            ['directBootAware', 'isolatedProcess'].each { attribute ->
                def task = fixture("<${kind} android:name=\".Unsupported\" android:${attribute}=\"true\" />")
                task.complete.set(true)
                String message = assertThrows(GradleException, { task.rewrite() }).message
                assertTrue(message, message.contains(kind + ' example.Unsupported'))
                assertTrue(message, message.contains('android:' + attribute + '=true'))
                assertFalse(task.outputManifest.get().asFile.exists())
            }
        }
        def app = fixture('', 'android:directBootAware="true"')
        app.complete.set(true)
        assertTrue(assertThrows(GradleException, { app.rewrite() }).message.contains('before unlock'))
    }

    @Test void completeProfileRejectsReservedShellComponents() {
        ['com.lelloman.paravoidandroid.runtime.LauncherActivity',
         'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity',
         'com.lelloman.paravoidandroid.runtime.PushForegroundService',
         'com.lelloman.paravoidandroid.runtime.UpdatesLauncher'].each { name ->
            ['activity', 'service', 'receiver', 'provider'].each { kind ->
                def task = fixture("<${kind} android:name=\"${name}\" />")
                task.complete.set(true)
                assertTrue(assertThrows(GradleException, { task.rewrite() }).message.contains("reserves shell ${kind} ${name}"))
            }
        }
    }

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
