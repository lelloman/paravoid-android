package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources as R
import com.android.aapt.ConfigurationOuterClass
import com.google.protobuf.UnknownFieldSet
import org.gradle.api.GradleException
import org.junit.Test
import static org.junit.Assert.*

class PinnedResourcesTest {
    private static final String APP = 'example.app'
    private static R.Item ref(int id) { R.Item.newBuilder().setRef(R.Reference.newBuilder().setId(id)).build() }
    private static R.Item str(String value) { R.Item.newBuilder().setStr(R.String.newBuilder().setValue(value)).build() }
    private static R.Value item(R.Item value) { R.Value.newBuilder().setItem(value).build() }
    private static R.XmlNode xml(List<R.XmlAttribute> attrs = []) {
        R.XmlNode.newBuilder().setElement(R.XmlElement.newBuilder().setName('manifest').addAllAttribute(attrs)).build()
    }
    private static R.XmlAttribute attribute(String name, int key, R.Item value) {
        R.XmlAttribute.newBuilder().setName(name).setResourceId(key).setCompiledItem(value).build()
    }
    private static R.ResourceTable table(Map<String, Map> definitions) {
        def pkg = R.Package.newBuilder().setPackageName(APP).setPackageId(R.PackageId.newBuilder().setId(0x7f))
        definitions.groupBy { name, value -> name.split('/')[0] }.each { typeName, entries ->
            def type = R.Type.newBuilder().setName(typeName).setTypeId(R.TypeId.newBuilder().setId((entries.values().first().id >> 16) & 0xff))
            entries.each { name, definition ->
                def entry = R.Entry.newBuilder().setName(name.split('/')[1]).setEntryId(R.EntryId.newBuilder().setId(definition.id & 0xffff))
                definition.values.eachWithIndex { value, index ->
                    entry.addConfigValue(R.ConfigValue.newBuilder().setValue(value).setConfig(
                        ConfigurationOuterClass.Configuration.newBuilder().setLocale(index == 0 ? '' : 'it')))
                }
                type.addEntry(entry)
            }
            pkg.addType(type)
        }
        R.ResourceTable.newBuilder().addPackage(pkg).build()
    }
    private static Map analyze(Map definitions, List<String> roots, R.XmlNode manifest = xml(), Map<String, byte[]> files = [:]) {
        PinnedResources.analyze(APP, table(definitions), manifest, roots, { files[it] }, { files[it] })
    }

    @Test void followsStyleParentsKeysArraysAndCyclesAcrossConfigurations() {
        def style = R.Style.newBuilder().setParent(R.Reference.newBuilder().setId(0x7f020001))
            .addEntry(R.Style.Entry.newBuilder().setKey(R.Reference.newBuilder().setId(0x7f030000)).setItem(ref(0x7f010000)))
        def values = [
            'style/Theme.App': [id: 0x7f020000, values: [R.Value.newBuilder().setCompoundValue(R.CompoundValue.newBuilder().setStyle(style)).build()]],
            'style/Parent': [id: 0x7f020001, values: [item(ref(0x7f020000))]],
            'attr/label': [id: 0x7f030000, values: [item(str('attr'))]],
            'string/title': [id: 0x7f010000, values: [item(str('Title')), item(ref(0x7f010001))]],
            'string/italian': [id: 0x7f010001, values: [item(str('Titolo'))]],
            'string/movable': [id: 0x7f010002, values: [item(str('Unpinned'))]],
            'array/options': [id: 0x7f040000, values: [R.Value.newBuilder().setCompoundValue(R.CompoundValue.newBuilder().setArray(
                R.Array.newBuilder().addElement(R.Array.Element.newBuilder().setItem(ref(0x7f010000))))).build()]]
        ]
        def report = analyze(values, ['array/options'], xml([attribute('theme', 0x01010000, ref(0x7f020000))]))
        assertEquals(['string/movable'], report.movable)
        assertEquals(6, report.pinned.size())
        assertEquals(['array/options', 'string/title', 'string/italian'], report.pinned.find { it.name == 'string/italian' }.chain)
        assertEquals(2, report.pinned.find { it.name == 'string/title' }.configurations)
    }

    @Test void followsCompiledXmlValuesAndCustomAttributeNames() {
        byte[] layout = xml([attribute('label', 0x7f030000, ref(0x7f010000))]).toByteArray()
        def report = analyze([
            'layout/widget': [id: 0x7f020000, values: [item(R.Item.newBuilder().setFile(R.FileReference.newBuilder()
                .setPath('res/layout/widget.xml').setType(R.FileReference.Type.PROTO_XML)).build())]],
            'attr/label': [id: 0x7f030000, values: [item(str('attr'))]],
            'string/title': [id: 0x7f010000, values: [item(str('Title'))]]
        ], ['layout/widget'], xml(), ['res/layout/widget.xml': layout])
        assertEquals(['attr/label', 'layout/widget', 'string/title'], report.pinned*.name)
        assertEquals(['layout/widget', 'attr/label'], report.pinned[0].chain)
    }

    @Test void doesNotInterpretStringsOrPrimitiveIntegersAsReferences() {
        def report = analyze([
            'string/literal': [id: 0x7f010000, values: [item(str('@0x7f019999'))]],
            'integer/literal': [id: 0x7f020000, values: [item(R.Item.newBuilder().setPrim(R.Primitive.newBuilder().setIntDecimalValue(0x7f019999)).build())]],
            'string/framework': [id: 0x7f010001, values: [item(ref(0x01010000)), item(ref(0))]]
        ], ['string/literal', 'integer/literal', 'string/framework'])
        assertEquals(3, report.pinned.size())
        assertTrue(report.pinned.every { it.dependencies.empty })
    }

    @Test void fingerprintsAllConfigurationsAndFilesButIgnoresMovableChanges() {
        def definitions = [
            'string/title': [id: 0x7f010000, values: [item(str('Title')), item(str('Titolo'))]],
            'string/movable': [id: 0x7f010001, values: [item(str('A'))]],
            'raw/data': [id: 0x7f020000, values: [item(R.Item.newBuilder().setFile(R.FileReference.newBuilder().setPath('res/raw/data.bin')).build())]]
        ]
        def a = analyze(definitions, ['string/title', 'raw/data'], xml(), ['res/raw/data.bin': [1] as byte[]])
        definitions['string/movable'].values = [item(str('B'))]
        def b = analyze(definitions, ['string/title', 'raw/data'], xml(), ['res/raw/data.bin': [1] as byte[]])
        assertTrue(PinnedResources.differences(a, b).empty)
        definitions['string/title'].values[1] = item(str('Changed Italian'))
        def c = analyze(definitions, ['string/title', 'raw/data'], xml(), ['res/raw/data.bin': [2] as byte[]])
        assertEquals(['Pinned resource changed: raw/data', 'Pinned resource changed: string/title'], PinnedResources.differences(a, c))
        def d = analyze(definitions, ['string/movable'])
        assertEquals(3, PinnedResources.differences(a, d).size())
    }

    @Test void rejectsUnresolvedRootsReferencesMissingFilesAndUnknownProtoFields() {
        def values = ['string/title': [id: 0x7f010000, values: [item(str('Title'))]]]
        assertThrows(GradleException, { analyze(values, ['string/missing']) })
        [0x7f019999, 0x02010000].each { bad ->
            values['string/title'].values = [item(ref(bad))]
            assertThrows(GradleException, { analyze(values, ['string/title']) })
        }
        values['string/title'].values = [item(R.Item.newBuilder().setFile(R.FileReference.newBuilder().setPath('res/raw/missing')).build())]
        assertThrows(GradleException, { analyze(values, ['string/title']) })
        def unknown = R.XmlNode.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder().addField(999,
            UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()).build()
        assertThrows(GradleException, { analyze([:], [], unknown) })
    }

    @Test void manifestChangesAreDetectedButApkVersionCodeAloneIsNot() {
        def first = xml([R.XmlAttribute.newBuilder().setName('versionCode').setNamespaceUri('http://schemas.android.com/apk/res/android').setValue('1').build()])
        def second = first.toBuilder().setElement(first.element.toBuilder().setAttribute(0, first.element.getAttribute(0).toBuilder().setValue('2'))).build()
        assertTrue(PinnedResources.differences(analyze([:], [], first), analyze([:], [], second)).empty)
        assertEquals(['Installed manifest changed'], PinnedResources.differences(analyze([:], []), analyze([:], [], xml([attribute('exported', 0, str('true'))]))))
        assertThrows(GradleException, { PinnedResources.differences([:], analyze([:], [])) })
    }
}
