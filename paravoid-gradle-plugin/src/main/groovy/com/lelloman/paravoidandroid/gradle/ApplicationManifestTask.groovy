package com.lelloman.paravoidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.api.provider.Property
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

@CacheableTask
abstract class ApplicationManifestTask extends DefaultTask {
    static final String ANDROID = 'http://schemas.android.com/apk/res/android'
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getInputManifest()
    @OutputFile abstract RegularFileProperty getOutputManifest()
    @OutputFile abstract RegularFileProperty getPayloadMetadata()
    @Input abstract Property<Boolean> getUpdatesEnabled()
    @Input abstract Property<Boolean> getPushEnabled()
    @Input abstract Property<Boolean> getPushBackgroundConnection()
    @Input abstract org.gradle.api.provider.ListProperty<String> getPushComponents()
    @Input abstract Property<Boolean> getComplete()
    @Input abstract Property<Boolean> getDebugHttpAllowed()
    @Input abstract Property<Boolean> getControlsLauncher()
    @Input abstract Property<Boolean> getCrashRecoveryEnabled()
    @Input abstract Property<String> getRecoveryProvider()
    ApplicationManifestTask() { pushBackgroundConnection.convention(false); pushComponents.convention([]); pushEnabled.convention(false); updatesEnabled.convention(false); complete.convention(false); debugHttpAllowed.convention(false); controlsLauncher.convention(false); crashRecoveryEnabled.convention(false); recoveryProvider.convention('') }

    @TaskAction void rewrite() {
        def factory = DocumentBuilderFactory.newInstance()
        factory.namespaceAware = true
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        def document = factory.newDocumentBuilder().parse(inputManifest.get().asFile)
        def app = (Element) document.getElementsByTagName('application').item(0)
        def activities = app.getElementsByTagName('activity')
        if (activities.length == 0 || app.getElementsByTagName('activity-alias').length != 0) {
            throw new GradleException('ParavoidAndroid requires at least one Activity and does not yet support Activity aliases.')
        }
        String componentFactory = app.getAttributeNS(ANDROID, 'appComponentFactory')
        if (componentFactory && !(componentFactory in ['android.app.AppComponentFactory', 'androidx.core.app.CoreComponentFactory'])) {
            throw new GradleException('ParavoidAndroid supports the default or AndroidX CoreComponentFactory only; custom Application/classloader factory hooks are not supported.')
        }
        String pkg = document.documentElement.getAttribute('package')
        if (complete.get()) {
            def reservedNames = [
                'com.lelloman.paravoidandroid.runtime.LauncherActivity',
                'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity',
                'com.lelloman.paravoidandroid.runtime.UpdatesLauncher',
                'com.lelloman.paravoidandroid.runtime.CrashRecoveryActivity',
                'com.lelloman.paravoidandroid.runtime.UpdatePromptActivity',
                'com.lelloman.paravoidandroid.runtime.RestartActivity',
                'com.lelloman.paravoidandroid.runtime.UpdateService',
                'com.lelloman.paravoidandroid.runtime.PushForegroundService',
                'com.lelloman.paravoidandroid.runtime.UpdateJobService',
                'com.lelloman.paravoidandroid.runtime.UpdateReceiver'
            ] as Set
            ['activity', 'service', 'receiver', 'provider'].each { kind ->
                def nodes = app.getElementsByTagName(kind)
                (0..<nodes.length).each { index ->
                    def node = (Element) nodes.item(index)
                    String name = ApplicationManifestTask.qualify(pkg, node.getAttributeNS(ANDROID, 'name'))
                    if (name in reservedNames)
                        throw new GradleException("Complete packaging reserves shell ${kind} ${name}; remove the declaration from the payload manifest.")
                    ['directBootAware', 'isolatedProcess'].each { attribute ->
                        String value = node.getAttributeNS(ANDROID, attribute)
                        if (value && value != 'false')
                            throw new GradleException("Complete packaging cannot run ${kind} ${name} with android:${attribute}=${value}; remove that component or use normal packaging.")
                    }
                }
            }
            if (app.getAttributeNS(ANDROID, 'directBootAware') && app.getAttributeNS(ANDROID, 'directBootAware') != 'false')
                throw new GradleException('Complete packaging cannot run a directBootAware Application before unlock; use normal packaging.')
            def existingMetadata = app.getElementsByTagName('meta-data')
            if ((0..<existingMetadata.length).any { existingMetadata.item(it).getAttributeNS(ANDROID, 'name').startsWith('paravoid.') })
                throw new GradleException('paravoid.* application metadata is reserved in complete packaging.')
            def allNodes = document.getElementsByTagName('*')
            (0..<allNodes.length).each { index ->
                def node = allNodes.item(index)
                String process = node.getAttributeNS(ANDROID, 'process')
                if (process in [':paravoid_recovery', pkg + ':paravoid_recovery', ':paravoid_updates', pkg + ':paravoid_updates'])
                    throw new GradleException('The :paravoid_recovery and :paravoid_updates processes are reserved for the shell.')
            }
            ['android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'].each { permission ->
                def permissions = document.getElementsByTagName('uses-permission')
                if (!(0..<permissions.length).any { permissions.item(it).getAttributeNS(ANDROID, 'name') == permission }) {
                    def node = document.createElement('uses-permission'); node.setAttributeNS(ANDROID, 'android:name', permission)
                    document.documentElement.insertBefore(node, app)
                }
            }
            if (debugHttpAllowed.get()) app.setAttributeNS(ANDROID, 'android:usesCleartextTraffic', 'true')
        }
        def activityNames = (0..<activities.length).collect { index ->
            def activity = (Element) activities.item(index)
            String name = ApplicationManifestTask.qualify(pkg, activity.getAttributeNS(ANDROID, 'name'))
            activity.setAttributeNS(ANDROID, 'android:name', name)
            name
        }
        if (activityNames.contains('com.lelloman.paravoidandroid.runtime.LauncherActivity')) {
            throw new GradleException('The Paravoid bootstrap Activity name is reserved.')
        }
        def serviceNodes = app.getElementsByTagName('service')
        def services = (0..<serviceNodes.length).collect { index ->
            ApplicationManifestTask.qualify(pkg, ((Element) serviceNodes.item(index)).getAttributeNS(ANDROID, 'name'))
        }
        String applicationName = app.getAttributeNS(ANDROID, 'name')
        if (applicationName) applicationName = qualify(pkg, applicationName)
        if (applicationName == 'android.app.Application' || applicationName == 'com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication') applicationName = ''
        def launcher = document.createElement('activity')
        launcher.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.runtime.LauncherActivity')
        launcher.setAttributeNS(ANDROID, 'android:exported', 'true')
        def launcherFilters = []
        def filters = app.getElementsByTagName('intent-filter')
        for (int i = 0; i < filters.length; i++) {
            def filter = (Element) filters.item(i)
            def actions = filter.getElementsByTagName('action')
            def categories = filter.getElementsByTagName('category')
            boolean main = (0..<actions.length).any { ((Element) actions.item(it)).getAttributeNS(ANDROID, 'name') == 'android.intent.action.MAIN' }
            boolean launch = (0..<categories.length).any { ((Element) categories.item(it)).getAttributeNS(ANDROID, 'name') == 'android.intent.category.LAUNCHER' }
            if (main && launch && filter.parentNode.nodeName == 'activity') launcherFilters.add(filter)
        }
        if (launcherFilters.size() != 1) throw new GradleException('ParavoidAndroid requires exactly one MAIN/LAUNCHER intent filter.')
        String activityName = ((Element) launcherFilters[0].parentNode).getAttributeNS(ANDROID, 'name')
        launcherFilters.each { launcher.appendChild(it) }
        app.appendChild(launcher)
        if (complete.get() && pushEnabled.get()) {
            if (pushBackgroundConnection.get()) {
                ['android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE'].each { permission ->
                    def nodes=document.getElementsByTagName('uses-permission')
                    if (!(0..<nodes.length).any { nodes.item(it).getAttributeNS(ANDROID,'name') == permission }) {
                        def node=document.createElement('uses-permission'); node.setAttributeNS(ANDROID,'android:name',permission)
                        document.documentElement.insertBefore(node,app)
                    }
                }
                def service=document.createElement('service')
                service.setAttributeNS(ANDROID,'android:name','com.lelloman.paravoidandroid.runtime.PushForegroundService')
                service.setAttributeNS(ANDROID,'android:process',':paravoid_updates')
                service.setAttributeNS(ANDROID,'android:exported','false')
                service.setAttributeNS(ANDROID,'android:foregroundServiceType','specialUse')
                def subtype=document.createElement('property')
                subtype.setAttributeNS(ANDROID,'android:name','android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE')
                subtype.setAttributeNS(ANDROID,'android:value','Maintain an ongoing app update event connection with a visible notification and user stop control')
                service.appendChild(subtype); app.appendChild(service)
                def background=document.createElement('meta-data')
                background.setAttributeNS(ANDROID,'android:name','paravoid.backgroundPush')
                background.setAttributeNS(ANDROID,'android:value','true'); app.appendChild(background)
            }
            Set<String> remaining=new HashSet<>(pushComponents.get())
            ['service','receiver'].each { kind ->
                def nodes=app.getElementsByTagName(kind)
                (0..<nodes.length).each { index ->
                    def node=nodes.item(index)
                    String name=ApplicationManifestTask.qualify(pkg,node.getAttributeNS(ANDROID,'name'))
                    if(remaining.remove(name)) node.setAttributeNS(ANDROID,'android:process',':paravoid_updates')
                }
            }
            if(!remaining.empty) throw new GradleException('Push components must be declared services or receivers: '+remaining)
            def routing=document.createElement('meta-data')
            routing.setAttributeNS(ANDROID,'android:name','paravoid.pushComponents')
            routing.setAttributeNS(ANDROID,'android:value',pushComponents.get().join(';')); app.appendChild(routing)
            def permission=document.createElement('uses-permission')
            permission.setAttributeNS(ANDROID,'android:name','android.permission.POST_NOTIFICATIONS'); document.documentElement.insertBefore(permission,app)
            def prompt=document.createElement('activity')
            prompt.setAttributeNS(ANDROID,'android:name','com.lelloman.paravoidandroid.runtime.UpdatePromptActivity')
            prompt.setAttributeNS(ANDROID,'android:process',':paravoid_updates'); prompt.setAttributeNS(ANDROID,'android:exported','false')
            prompt.setAttributeNS(ANDROID,'android:theme','@android:style/Theme.Material.Light.Dialog.Alert'); app.appendChild(prompt)
        }
        if (complete.get() && updatesEnabled.get()) {
            def restart=document.createElement('activity')
            restart.setAttributeNS(ANDROID,'android:name','com.lelloman.paravoidandroid.runtime.RestartActivity')
            restart.setAttributeNS(ANDROID,'android:process',':paravoid_recovery')
            restart.setAttributeNS(ANDROID,'android:exported','false')
            restart.setAttributeNS(ANDROID,'android:theme','@android:style/Theme.Material.Light.Dialog.Alert')
            app.appendChild(restart)
            ['android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.RECEIVE_BOOT_COMPLETED'].each { permission ->
                def nodes=document.getElementsByTagName('uses-permission')
                if (!(0..<nodes.length).any { nodes.item(it).getAttributeNS(ANDROID,'name') == permission }) {
                    def node=document.createElement('uses-permission'); node.setAttributeNS(ANDROID,'android:name',permission)
                    document.documentElement.insertBefore(node,app)
                }
            }
            ['UpdateService','UpdateJobService'].each { name ->
                def node=document.createElement('service')
                node.setAttributeNS(ANDROID,'android:name','com.lelloman.paravoidandroid.runtime.'+name)
                node.setAttributeNS(ANDROID,'android:process',':paravoid_updates')
                node.setAttributeNS(ANDROID,'android:exported',name=='UpdateJobService' ? 'true' : 'false')
                if(name=='UpdateJobService') node.setAttributeNS(ANDROID,'android:permission','android.permission.BIND_JOB_SERVICE')
                app.appendChild(node)
            }
            def receiver=document.createElement('receiver')
            receiver.setAttributeNS(ANDROID,'android:name','com.lelloman.paravoidandroid.runtime.UpdateReceiver')
            receiver.setAttributeNS(ANDROID,'android:process',':paravoid_updates'); receiver.setAttributeNS(ANDROID,'android:exported','false')
            def filter=document.createElement('intent-filter')
            ['android.intent.action.BOOT_COMPLETED','android.intent.action.MY_PACKAGE_REPLACED','android.intent.action.USER_UNLOCKED'].each { name ->
                def action=document.createElement('action'); action.setAttributeNS(ANDROID,'android:name',name); filter.appendChild(action)
            }
            receiver.appendChild(filter); app.appendChild(receiver)
        }
        if (complete.get()) {
            def recovery = document.createElement('activity')
            recovery.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity')
            recovery.setAttributeNS(ANDROID, 'android:process', ':paravoid_recovery')
            recovery.setAttributeNS(ANDROID, 'android:exported', 'false')
            recovery.setAttributeNS(ANDROID, 'android:theme', '@android:style/Theme.Material.Light.NoActionBar')
            app.appendChild(recovery)
            if (crashRecoveryEnabled.get()) {
                def crash = document.createElement('activity')
                crash.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.runtime.CrashRecoveryActivity')
                crash.setAttributeNS(ANDROID, 'android:process', ':paravoid_recovery')
                crash.setAttributeNS(ANDROID, 'android:exported', 'false')
                crash.setAttributeNS(ANDROID, 'android:theme', '@android:style/Theme.Material.Light.NoActionBar')
                app.appendChild(crash)
                ['paravoid.crashRecovery': 'true', 'paravoid.recoveryProvider': recoveryProvider.get()].each { key, value ->
                    def meta = document.createElement('meta-data')
                    meta.setAttributeNS(ANDROID, 'android:name', key); meta.setAttributeNS(ANDROID, 'android:value', value)
                    app.appendChild(meta)
                }
            }
            if (controlsLauncher.get()) {
                // Only this shell-owned alias is exported. The target stays private and
                // executes in the payload-free recovery process, even on a cold launch.
                def alias = document.createElement('activity-alias')
                alias.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.runtime.UpdatesLauncher')
                alias.setAttributeNS(ANDROID, 'android:targetActivity', 'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity')
                alias.setAttributeNS(ANDROID, 'android:label', 'App updates')
                alias.setAttributeNS(ANDROID, 'android:exported', 'true')
                alias.setAttributeNS(ANDROID, 'android:permission', '')
                def filter = document.createElement('intent-filter')
                def action = document.createElement('action')
                action.setAttributeNS(ANDROID, 'android:name', 'android.intent.action.MAIN')
                def category = document.createElement('category')
                category.setAttributeNS(ANDROID, 'android:name', 'android.intent.category.LAUNCHER')
                filter.appendChild(action); filter.appendChild(category); alias.appendChild(filter)
                app.appendChild(alias)
            }
            def marker = document.createElement('meta-data')
            marker.setAttributeNS(ANDROID, 'android:name', 'paravoid.complete'); marker.setAttributeNS(ANDROID, 'android:value', 'true')
            app.appendChild(marker)
        }
        app.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.runtime.ShellApplication')
        app.setAttributeNS(ANDROID, 'android:appComponentFactory', 'com.lelloman.paravoidandroid.runtime.ParavoidComponentFactory')
        ['paravoid.activity': activityName, 'paravoid.application': applicationName,
         'paravoid.componentFactory': componentFactory].each { name, value ->
            if (value) {
                def metadata = document.createElement('meta-data')
                metadata.setAttributeNS(ANDROID, 'android:name', name)
                metadata.setAttributeNS(ANDROID, 'android:value', value)
                app.appendChild(metadata)
            }
        }
        def output = outputManifest.get().asFile
        output.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(output))
        def metadata = payloadMetadata.get().asFile
        metadata.parentFile.mkdirs()
        metadata.text = "activity=${activityName}\nactivities=${activityNames.join(';')}\napplication=${applicationName}\nservices=${services.join(';')}\ncomplete=${complete.get()}\n"
    }

    private static String qualify(String pkg, String name) {
        name.startsWith('.') ? pkg + name : (name.contains('.') ? name : pkg + '.' + name)
    }
}
