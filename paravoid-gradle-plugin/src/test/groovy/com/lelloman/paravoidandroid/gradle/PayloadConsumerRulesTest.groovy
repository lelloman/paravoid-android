package com.lelloman.paravoidandroid.gradle

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class PayloadConsumerRulesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void mergesExtractedRulesWithoutDependingOnCachePathsOrRepeatingDuplicates() {
        File aar = temporary.newFolder('aar'), jar = temporary.newFolder('jar')
        File a = new File(aar, 'proguard.txt'); a.text = '-keep class example.A { *; }\n'
        File j = new File(jar, 'proguard.txt'); j.text = '-keep class example.J { *; }\n'
        File duplicate = temporary.newFile('same.pro'); duplicate.bytes = a.bytes
        String merged = PayloadConsumerRules.merge([aar, jar, duplicate] as Set, '8.10.9-dev')
        assertEquals(merged, PayloadConsumerRules.merge([j, a] as Set, '8.10.9-dev'))
        assertEquals(1, merged.count('-keep class example.A'))
        assertTrue(merged.contains('-keep class example.J'))
        assertFalse(merged.contains(temporary.root.absolutePath))
        j.text = '-keep class example.Changed { *; }\n'
        assertNotEquals(merged, PayloadConsumerRules.merge([aar, jar] as Set, '8.10.9-dev'))
    }
    @Test void selectsVersionedR8RulesAndDoesNotMixLegacyFallback() {
        File extracted = temporary.newFolder('extracted')
        File lib = new File(extracted, 'lib')
        File old = new File(lib, 'META-INF/com.android.tools/r8-upto-8.0.0/old.pro')
        File current = new File(lib, 'META-INF/com.android.tools/r8-from-8.0.0-upto-9.0.0/current.pro')
        File future = new File(lib, 'META-INF/com.android.tools/r8-from-9.0.0/future.pro')
        File fallback = new File(lib, 'META-INF/proguard/legacy.pro')
        [old, current, future, fallback].each { it.parentFile.mkdirs(); it.text = "# ${it.name}" }
        String merged = PayloadConsumerRules.merge([extracted] as Set, '8.10.9-dev')
        assertTrue(merged.contains('# current.pro'))
        assertFalse(merged.contains('# old.pro'))
        assertFalse(merged.contains('# future.pro'))
        assertFalse(merged.contains('# legacy.pro'))
        assertTrue(PayloadConsumerRules.merge([extracted] as Set, '9.1.0').contains('# future.pro'))
    }

}
