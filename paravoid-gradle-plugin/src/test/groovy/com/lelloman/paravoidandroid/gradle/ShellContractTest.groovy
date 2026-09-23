package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class ShellContractTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder()
    private static Map descriptor() {
        [profile: 'embedded-apk-v1', applicationId: 'example.app', minSdk: 30,
         manifestSha256: 'a' * 64, declarations: [:], pinnedResources: [:],
         runtimeClasses: ['runtime.class': 'b' * 64], nativeAbis: [:],
         ledgerReservations: ['string/title': '0x7f010000'], apkSigners: ['c' * 64],
         distribution: [bootstrap: 'embedded', updates: false], toolchain: [agp: '8.13.2']]
    }
    private static byte[] bytes(Map value) { (CanonicalJson.encode(value) + '\n').getBytes('UTF-8') }

    @Test void canonicalEncodingAndStrictRoundTrip() {
        assertEquals('{"a":[true,null,30],"z":"é😀\\n\\t\\u0000"}', CanonicalJson.encode([z: 'é😀\n\t\u0000', a: [true, null, 30]]))
        def original = ShellContract.document(descriptor())
        assertEquals(original, ShellContract.read(bytes(original)))
        assertEquals(original.contractId, ShellContract.document(new TreeMap(descriptor())).contractId)
        [1.5, -1, 9007199254740992L, new String([0xd800 as char] as char[])].each { value ->
            assertThrows(GradleException, { CanonicalJson.encode(value) })
        }
    }

    @Test void rejectsTamperingDuplicatesAndNoncanonicalSnapshots() {
        String valid = new String(bytes(ShellContract.document(descriptor())), 'UTF-8')
        [valid.trim(), ' ' + valid, valid.replace('"version":1', '"version":1,"version":1'),
         valid.replace('"minSdk":30', '"minSdk":31'), valid.replace('"version":1', '"version":2')].each { text ->
            assertThrows(GradleException, { ShellContract.read(text.getBytes('UTF-8')) })
        }
    }

    @Test void concreteInstalledBoundaryDiffs() {
        ['manifestSha256', 'runtimeClasses', 'nativeAbis', 'apkSigners', 'pinnedResources', 'declarations', 'toolchain'].each { field ->
            Map next = descriptor()
            if (field == 'manifestSha256') next[field] = 'd' * 64
            else if (field == 'apkSigners') next[field] = ['d' * 64]
            else if (field == 'pinnedResources') next[field] = ['string/title': [id: '0x7f010000', sha256: 'd' * 64]]
            else next[field] = [added: 'd' * 64]
            assertTrue(ShellContract.differences(descriptor(), next).any { it.contains(field) })
            assertNotEquals(ShellContract.document(descriptor()).contractId, ShellContract.document(next).contractId)
        }
    }

    @Test void payloadLedgerCanGrowAndTombstoneWithoutChangingInstalledAnchor() {
        def ledger = new ResourceLedger('example.app', [[name: 'string/title', id: '0x7f010000', removed: true],
            [name: 'string/new', id: '0x7f010001', removed: false]])
        assertEquals(descriptor().ledgerReservations, ShellContract.reservations(ledger, descriptor()))
        assertEquals(2, ShellContract.reservations(ledger, null).size())
        assertThrows(GradleException, { ShellContract.reservations(new ResourceLedger('example.app', []), descriptor()) })
    }

    @Test void manifestDiagnosticsIgnoreOnlyVersionCodeAndNormalizePrefixes() {
        File a = tmp.newFile('a.xml'), b = tmp.newFile('b.xml')
        a.text = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="1"><application android:name="example.App"/></manifest>'
        b.text = a.text.replace('android:', 'a:').replace('xmlns:android', 'xmlns:a').replace('="1"', '="2"')
        assertEquals(GenerateShellContractTask.declarations(a), GenerateShellContractTask.declarations(b))
        b.text = b.text.replace('example.App', 'example.Other')
        assertNotEquals(GenerateShellContractTask.declarations(a), GenerateShellContractTask.declarations(b))
    }

    @Test void completeBaselineDetectsEachInstalledComponentKind() {
        File manifest = tmp.newFile('components.xml')
        manifest.text = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app">
            <application><activity android:name="example.Main" /></application></manifest>'''
        Map accepted = descriptor()
        accepted.declarations = GenerateShellContractTask.declarations(manifest)
        [activity: 'example.OtherActivity', service: 'example.Worker',
         receiver: 'example.Boot', provider: 'example.Data'].each { kind, name ->
            manifest.text = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app">
                <application><activity android:name="example.Main" /><${kind} android:name="${name}" /></application></manifest>"""
            Map candidate = descriptor()
            candidate.declarations = GenerateShellContractTask.declarations(manifest)
            assertTrue(kind, ShellContract.differences(accepted, candidate).any { it.contains('/' + kind + '[') })
            assertNotEquals(kind, ShellContract.document(accepted).contractId, ShellContract.document(candidate).contractId)
        }
    }
}
