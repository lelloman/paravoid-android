package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources as R
import com.android.aapt.ConfigurationOuterClass
import org.gradle.api.GradleException
import org.junit.Test
import static org.junit.Assert.*

class ResourceTableSubsetTest {
    private static R.ResourceTable table() {
        def pkg = R.Package.newBuilder().setPackageName('example.app').setPackageId(R.PackageId.newBuilder().setId(0x7f))
        [string: 3, style: 8].each { name, id ->
            def type = R.Type.newBuilder().setName(name).setTypeId(R.TypeId.newBuilder().setId(id))
            [keep: 2, remove: 9].each { entryName, entryId ->
                def entry = R.Entry.newBuilder().setName(entryName).setEntryId(R.EntryId.newBuilder().setId(entryId))
                ['', 'it'].each { locale ->
                    entry.addConfigValue(R.ConfigValue.newBuilder().setConfig(ConfigurationOuterClass.Configuration.newBuilder().setLocale(locale))
                        .setValue(R.Value.newBuilder().setItem(R.Item.newBuilder().setStr(R.String.newBuilder().setValue(locale + entryName)))))
                }
                type.addEntry(entry)
            }
            pkg.addType(type)
        }
        R.ResourceTable.newBuilder().addPackage(pkg).build()
    }

    @Test void preservesIdsAndAllConfigurationsWithoutChangingSource() {
        def input = table()
        def subset = ResourceTableSubset.retain(input, ['style/keep'] as Set)
        assertEquals(0x7f, subset.getPackage(0).packageId.id)
        assertEquals(1, subset.getPackage(0).typeCount)
        def type = subset.getPackage(0).getType(0)
        assertEquals(8, type.typeId.id)
        assertEquals(1, type.entryCount)
        assertEquals(input.getPackage(0).getType(1).getEntry(0), type.getEntry(0))
        assertEquals(2, type.getEntry(0).entryId.id)
        assertEquals(2, type.getEntry(0).configValueCount)
        assertEquals(table(), input)
    }

    @Test void supportsEmptySubsetAndRejectsMissingNames() {
        assertEquals(0, ResourceTableSubset.retain(table(), [] as Set).getPackage(0).typeCount)
        assertThrows(GradleException, { ResourceTableSubset.retain(table(), ['string/typo'] as Set) })
    }
}
