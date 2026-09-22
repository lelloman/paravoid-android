package com.lelloman.paravoidandroid.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.security.MessageDigest

/** Build-time embedded-profile contract. Not the future signed full-VPK wire format. */
final class ShellContract {
    static final Set FIELDS = ['profile', 'applicationId', 'minSdk', 'manifestSha256', 'declarations',
        'pinnedResources', 'runtimeClasses', 'nativeAbis', 'ledgerReservations', 'apkSigners', 'distribution', 'toolchain'] as Set

    static String sha(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }

    static Map document(Map descriptor) {
        validate(descriptor)
        [version: 1, contractId: sha(CanonicalJson.encode(descriptor).getBytes('UTF-8')), descriptor: descriptor]
    }

    static Map reservations(ResourceLedger ledger, Map accepted) {
        Map current = ledger.entries.collectEntries { [(it.name): it.id] }
        if (accepted == null) return current
        validate(accepted)
        if (accepted.applicationId != ledger.applicationId) throw new GradleException('Shell contract application ID changed')
        accepted.ledgerReservations.each { name, id ->
            if (current[name] != id) throw new GradleException('Installed shell resource reservation changed: ' + name)
        }
        new LinkedHashMap(accepted.ledgerReservations)
    }

    static Map read(byte[] bytes) {
        String text = new String(bytes, 'UTF-8')
        def value
        try { value = new JsonSlurper().parseText(text) }
        catch (Exception error) { throw new GradleException('Invalid shell contract JSON', error) }
        if (!(value instanceof Map) || value.keySet() != ['version', 'contractId', 'descriptor'] as Set || value.version != 1 || !(value.descriptor instanceof Map))
            throw new GradleException('Invalid shell contract fields/version')
        // Generated snapshots are canonical. This also rejects duplicate keys, lossy
        // Unicode decoding and noncanonical number spellings without trusting JsonSlurper.
        byte[] canonical = (CanonicalJson.encode(value) + '\n').getBytes('UTF-8')
        if (!Arrays.equals(bytes, canonical)) throw new GradleException('Shell contract must be canonical generated JSON (including final newline)')
        Map checked = document(value.descriptor)
        if (checked.contractId != value.contractId) throw new GradleException('Shell contract ID does not match descriptor')
        checked
    }

    static void validate(Map descriptor) {
        if (descriptor.keySet() != FIELDS || descriptor.profile != 'embedded-apk-v1' ||
            descriptor.distribution != [bootstrap: 'embedded', updates: false] ||
            !(descriptor.applicationId instanceof String) || !(descriptor.minSdk instanceof Integer) || descriptor.minSdk < 30 ||
            !(descriptor.manifestSha256 ==~ /[0-9a-f]{64}/) ||
            !['declarations', 'pinnedResources', 'runtimeClasses', 'nativeAbis', 'ledgerReservations', 'toolchain'].every { descriptor[it] instanceof Map } ||
            !(descriptor.apkSigners instanceof List) || descriptor.apkSigners.empty ||
            !descriptor.apkSigners.every { it ==~ /[0-9a-f]{64}/ })
            throw new GradleException('Invalid or unsupported embedded shell contract descriptor')
        new ResourceLedger(descriptor.applicationId, descriptor.ledgerReservations.collect { name, id -> [name: name, id: id, removed: false] })
        if (!['runtimeClasses', 'nativeAbis'].every { field -> descriptor[field].every { name, hash -> name instanceof String && hash instanceof String && hash ==~ /[0-9a-f]{64}/ } } ||
            !['declarations', 'toolchain'].every { field -> descriptor[field].every { name, value -> name instanceof String && value instanceof String } } ||
            !descriptor.pinnedResources.every { name, value -> value instanceof Map && value.keySet() == ['id', 'sha256'] as Set &&
                value.id instanceof String && value.id ==~ /0x7f[0-9a-f]{6}/ && value.sha256 instanceof String && value.sha256 ==~ /[0-9a-f]{64}/ })
            throw new GradleException('Invalid shell contract boundary entries')
        CanonicalJson.encode(descriptor)
    }

    static List<String> differences(Map accepted, Map candidate) {
        validate(accepted); validate(candidate)
        List<String> changes = []
        compare('', accepted, candidate, changes)
        changes
    }

    private static void compare(String path, Object a, Object b, List<String> changes) {
        if (a instanceof Map && b instanceof Map) {
            (a.keySet() + b.keySet()).toSet().sort().each { key ->
                String next = path ? path + '/' + key : key
                if (!a.containsKey(key)) changes.add('Added ' + next + ': ' + brief(b[key]))
                else if (!b.containsKey(key)) changes.add('Removed ' + next + ': ' + brief(a[key]))
                else compare(next, a[key], b[key], changes)
            }
        } else if (a != b) {
            changes.add('Changed ' + path + ': ' + brief(a) + ' -> ' + brief(b))
        }
    }

    private static String brief(Object value) {
        String text = CanonicalJson.encode(value)
        text.length() > 180 ? text.substring(0, 180) + '...' : text
    }
}
