package com.lelloman.paravoidandroid.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

/** Exact linked names, not Java R names (which lose dots in style names). */
final class ResourceLedger {
    final String applicationId
    final List<Map> entries

    ResourceLedger(String applicationId, List<Map> entries) {
        require(applicationId ==~ /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+/, 'Invalid applicationId')
        Set names = [], ids = []
        Map types = [:], typeIds = [:]
        List<Map> validated = entries.collect { entry ->
            require(entry.keySet() == ['name', 'id', 'removed'] as Set, 'Invalid resource entry fields')
            // AAPT-generated animated-vector resources contain '$'; preserve linked names exactly.
            require(entry.name instanceof String && entry.name ==~ /[a-z][a-z0-9_]*\/[A-Za-z_$][A-Za-z0-9_.$]*/, "Invalid resource name: ${entry.name}")
            require(entry.id instanceof String && entry.id ==~ /0x7f[0-9a-f]{6}/, "Invalid resource ID: ${entry.id}")
            require(entry.removed instanceof Boolean, "Invalid tombstone: ${entry.name}")
            require(names.add(entry.name), "Duplicate resource name: ${entry.name}")
            require(ids.add(entry.id), "Reused resource ID: ${entry.id}")
            String type = entry.name.split('/')[0], typeId = entry.id.substring(4, 6)
            require(typeId != '00', "Invalid type ID: ${entry.id}")
            require(!types.containsKey(type) || types[type] == typeId, "Type ID changed: ${type}")
            require(!typeIds.containsKey(typeId) || typeIds[typeId] == type, "Type ID reused: ${typeId}")
            types[type] = typeId
            typeIds[typeId] = type
            Collections.unmodifiableMap(new LinkedHashMap(entry))
        }.sort { it.name }
        this.entries = Collections.unmodifiableList(validated)
        this.applicationId = applicationId
    }

    static ResourceLedger read(String json) {
        def data
        try { data = new JsonSlurper().parseText(json) }
        catch (Exception error) { throw new GradleException('Invalid resource ledger JSON', error) }
        require(data instanceof Map && data.keySet() == ['version', 'applicationId', 'entries'] as Set,
            'Invalid resource ledger fields')
        require(data.version instanceof Integer && data.version == 1, 'Unsupported resource ledger version')
        require(data.applicationId instanceof String && data.entries instanceof List && data.entries.every { it instanceof Map },
            'Invalid resource ledger contents')
        new ResourceLedger(data.applicationId, data.entries)
    }

    static ResourceLedger fromDump(String applicationId, String dump, ResourceLedger baseline = null) {
        // AAPT2 also emits an empty resources.arsc (zero packages) for resource-free apps.
        if (dump.trim() == 'Binary APK') return reconcile(applicationId, [], baseline)
        List<Map> current = []
        int packages = 0
        dump.eachLine { line ->
            if (line.startsWith('Package ')) {
                require(line == "Package name=${applicationId} id=7f", "Unexpected resource package: ${line}")
                packages++
            } else if (line.trim().startsWith('resource ')) {
                def match = line =~ /^\s*resource (0x[0-9a-f]{8}) ([^\s]+)(?: .*)?$/
                require(match.matches() && packages == 1, "Unrecognized AAPT2 resource line: ${line}")
                current.add([name: match[0][2], id: match[0][1], removed: false])
            }
        }
        require(packages == 1, 'Expected one linked application resource package')
        reconcile(applicationId, current, baseline)
    }

    static ResourceLedger reconcile(String applicationId, List<Map> entries, ResourceLedger baseline = null) {
        require(baseline == null || baseline.applicationId == applicationId, 'Resource baseline applicationId mismatch')
        List<Map> current = new ArrayList<>(entries)
        // Validate before merging, so duplicate table entries cannot disappear in a map.
        def linked = new ResourceLedger(applicationId, current)
        Map byName = linked.entries.collectEntries { [(it.name): it] }
        baseline?.entries?.each { previous ->
            if (byName.containsKey(previous.name)) {
                require(byName[previous.name].id == previous.id, "Resource ID changed: ${previous.name} (${previous.id} -> ${byName[previous.name].id})")
            } else {
                current.add([name: previous.name, id: previous.id, removed: true])
            }
        }
        // Also detects a new name stealing a removed entry/type ID.
        new ResourceLedger(applicationId, current)
    }

    String toJson() {
        JsonOutput.prettyPrint(JsonOutput.toJson([version: 1, applicationId: applicationId, entries: entries])) + '\n'
    }

    String toStableIds() {
        entries.collect { "${applicationId}:${it.name} = ${it.id}\n" }.join('')
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new GradleException("Paravoid resource ledger: ${message}")
    }
}
