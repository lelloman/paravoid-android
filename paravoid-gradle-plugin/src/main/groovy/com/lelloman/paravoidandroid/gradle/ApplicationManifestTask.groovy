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
    @Input abstract Property<Boolean> getComplete()
    @Input abstract Property<Boolean> getDebugHttpAllowed()
    ApplicationManifestTask() { complete.convention(false); debugHttpAllowed.convention(false) }

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
                'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity'
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
                if (process in [':paravoid_recovery', pkg + ':paravoid_recovery'])
                    throw new GradleException('The :paravoid_recovery process is reserved for shell-only controls.')
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
        if (complete.get()) {
            def recovery = document.createElement('activity')
            recovery.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity')
            recovery.setAttributeNS(ANDROID, 'android:process', ':paravoid_recovery')
            recovery.setAttributeNS(ANDROID, 'android:exported', 'false')
            recovery.setAttributeNS(ANDROID, 'android:theme', '@android:style/Theme.Material.Light.NoActionBar')
            app.appendChild(recovery)
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
