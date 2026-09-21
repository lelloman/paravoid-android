package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.junit.Test
import static org.junit.Assert.*

class ResourceLedgerTest {
    private static final String APP = 'example.app'
    private static String dump(String lines) { "Binary APK\nPackage name=${APP} id=7f\n" + lines }
    private static ResourceLedger first() {
        ResourceLedger.fromDump(APP, dump('''
            resource 0x7f010000 string/old
            resource 0x7f010001 string/title
            resource 0x7f020000 style/Theme.App PUBLIC
        '''))
    }

    @Test void roundTripsAndPreservesExactNames() {
        def ledger = first()
        assertEquals(ledger.toJson(), ResourceLedger.read(ledger.toJson()).toJson())
        assertEquals('''example.app:string/old = 0x7f010000
example.app:string/title = 0x7f010001
example.app:style/Theme.App = 0x7f020000
''', ledger.toStableIds())
        assertThrows(UnsupportedOperationException, { ledger.entries.clear() })
        assertThrows(UnsupportedOperationException, { ledger.entries[0].id = '0x7f010099' })
    }

    @Test void removesAddsAndRestoresWithoutReusingIdsOrMutatingBaseline() {
        def a = first()
        String original = a.toJson()
        def b = ResourceLedger.fromDump(APP, dump('''
            resource 0x7f010001 string/title
            resource 0x7f010002 string/added
        '''), a)
        assertTrue(b.entries.find { it.name == 'string/old' }.removed)
        assertTrue(b.entries.find { it.name == 'style/Theme.App' }.removed)
        assertTrue(b.toStableIds().contains('style/Theme.App = 0x7f020000'))
        assertEquals(original, a.toJson())
        def c = ResourceLedger.fromDump(APP, dump('resource 0x7f010000 string/old'), b)
        assertFalse(c.entries.find { it.name == 'string/old' }.removed)
        assertTrue(c.entries.find { it.name == 'string/added' }.removed)
        assertEquals(4, c.entries.size())
    }

    @Test void rejectsChangedOrStolenIdsAndTypeIds() {
        [
            'resource 0x7f010002 string/title',
            'resource 0x7f010000 string/new_name',
            'resource 0x7f020001 color/new_color',
            'resource 0x7f030000 string/new_type_id',
        ].each { lines -> assertThrows(GradleException, { ResourceLedger.fromDump(APP, dump(lines), first()) }) }
    }

    @Test void rejectsWrongPackagesAndMalformedDumpInsteadOfExportingPartialLedger() {
        [
            '', 'Binary APK\n', dump('resource nonsense'),
            dump('resource 0x80010000 string/title'),
            dump('resource 0x7f000000 string/title'),
            dump('resource 0x7f010000 string/title\nresource 0x7f010000 string/title'),
            dump('resource 0x7f010000 string/title\nresource 0x7f010000 string/other'),
            dump('') + 'Package name=other.app id=7f\n',
            dump('').replace(APP, 'other.app')
        ].each { text -> assertThrows(GradleException, { ResourceLedger.fromDump(APP, text) }) }
        assertThrows(GradleException, { ResourceLedger.fromDump('other.app', dump(''), first()) })
    }

    @Test void validatesPersistedLedgerAndSchemaVersion() {
        ['{}', '[]', '{broken', first().toJson().replace('"version": 1', '"version": 2'),
         first().toJson().replace('"removed": false', '"removed": "false"'),
         first().toJson().replace('0x7f010001', '0x7f010000'),
         first().toJson().replace('Theme.App', '../bad')].each { text ->
            assertThrows(GradleException, { ResourceLedger.read(text) })
        }
    }

    @Test void deterministicAcrossResourceOrderAndEmptyTable() {
        assertEquals(ResourceLedger.fromDump(APP, dump('resource 0x7f010000 string/a\nresource 0x7f010001 string/b')).toJson(),
            ResourceLedger.fromDump(APP, dump('resource 0x7f010001 string/b\nresource 0x7f010000 string/a')).toJson())
        assertEquals('', ResourceLedger.fromDump(APP, dump('')).toStableIds())
    }
}
