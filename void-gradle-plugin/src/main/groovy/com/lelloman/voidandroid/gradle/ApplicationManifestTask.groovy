package com.lelloman.voidandroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
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

    @TaskAction void rewrite() {
        def factory = DocumentBuilderFactory.newInstance()
        factory.namespaceAware = true
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        def document = factory.newDocumentBuilder().parse(inputManifest.get().asFile)
        def app = (Element) document.getElementsByTagName('application').item(0)
        def activities = app.getElementsByTagName('activity')
        if (activities.length != 1 || app.getElementsByTagName('activity-alias').length != 0) {
            throw new GradleException('VoidAndroid requires exactly one user Activity and no Activity aliases in the merged manifest (including dependencies).')
        }
        ['service', 'receiver', 'provider'].each { tag ->
            if (app.getElementsByTagName(tag).length != 0) {
                throw new GradleException("VoidAndroid example does not yet support manifest ${tag} components.")
            }
        }
        if (app.hasAttributeNS(ANDROID, 'appComponentFactory')) {
            throw new GradleException('VoidAndroid example does not yet compose custom appComponentFactory implementations.')
        }
        def activity = (Element) activities.item(0)
        String pkg = document.documentElement.getAttribute('package')
        String activityName = qualify(pkg, activity.getAttributeNS(ANDROID, 'name'))
        String applicationName = app.getAttributeNS(ANDROID, 'name')
        if (applicationName) applicationName = qualify(pkg, applicationName)
        if (applicationName == 'android.app.Application' || applicationName == 'com.lelloman.voidandroid.runtime.VoidAndroidApplication') applicationName = ''
        activity.setAttributeNS(ANDROID, 'android:name', activityName)
        def launcher = document.createElement('activity')
        launcher.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.voidandroid.runtime.LauncherActivity')
        launcher.setAttributeNS(ANDROID, 'android:exported', 'true')
        def filters = activity.getElementsByTagName('intent-filter')
        def launcherFilters = []
        for (int i = 0; i < filters.length; i++) {
            def filter = (Element) filters.item(i)
            def actions = filter.getElementsByTagName('action')
            def categories = filter.getElementsByTagName('category')
            boolean main = (0..<actions.length).any { ((Element) actions.item(it)).getAttributeNS(ANDROID, 'name') == 'android.intent.action.MAIN' }
            boolean launch = (0..<categories.length).any { ((Element) categories.item(it)).getAttributeNS(ANDROID, 'name') == 'android.intent.category.LAUNCHER' }
            if (main && launch) launcherFilters.add(filter)
        }
        if (launcherFilters.size() != 1) throw new GradleException('VoidAndroid requires exactly one MAIN/LAUNCHER intent filter.')
        launcherFilters.each { launcher.appendChild(it) }
        app.appendChild(launcher)
        app.setAttributeNS(ANDROID, 'android:name', 'com.lelloman.voidandroid.runtime.ShellApplication')
        app.setAttributeNS(ANDROID, 'android:appComponentFactory', 'com.lelloman.voidandroid.runtime.VoidComponentFactory')
        ['void.activity': activityName, 'void.application': applicationName].each { name, value ->
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
        metadata.text = "activity=${activityName}\napplication=${applicationName}\n"
    }

    private static String qualify(String pkg, String name) {
        name.startsWith('.') ? pkg + name : (name.contains('.') ? name : pkg + '.' + name)
    }
}
