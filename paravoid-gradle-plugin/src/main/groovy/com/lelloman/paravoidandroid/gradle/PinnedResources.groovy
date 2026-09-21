package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources
import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import groovy.json.JsonOutput
import org.gradle.api.GradleException
import java.security.MessageDigest

/** Analysis of linked AAPT2 protobufs, not a resource splitter or complete shell contract. */
final class PinnedResources {
    private static final Set<String> SOURCE_FIELDS = ['source', 'source_position', 'comment'] as Set

    static Map analyze(String applicationId, Resources.ResourceTable table, Resources.XmlNode manifest,
                       Collection<String> extraRoots, Closure<byte[]> originalFile, Closure<byte[]> protoFile) {
        Map<String, Map> nodes = [:]
        Map<Integer, String> names = [:]
        require(table.unknownFields.asMap().isEmpty(), 'Unknown AAPT2 resource table fields')
        require(table.dynamicRefTableCount == 0, 'Dynamic resource packages are not supported')
        require(table.overlayableCount == 0, 'Runtime resource overlays need a separate boundary policy')
        table.packageList.each { pkg ->
            require(pkg.unknownFields.asMap().isEmpty(), 'Unknown AAPT2 resource package fields')
            require(pkg.packageName == applicationId && pkg.packageId.id == 0x7f, 'Expected only the linked application resource package')
            pkg.typeList.each { type ->
                require(type.unknownFields.asMap().isEmpty(), 'Unknown AAPT2 resource type fields')
                require(type.hasTypeId() && type.typeId.id in 1..255, 'Invalid linked resource type ID')
                type.entryList.each { entry ->
                    require(entry.hasEntryId() && entry.entryId.id in 0..65535, 'Invalid linked resource entry ID')
                    String name = "${type.name}/${entry.name}"
                    int id = (0x7f << 24) | (type.typeId.id << 16) | entry.entryId.id
                    require(!nodes.containsKey(name) && !names.containsKey(id), "Duplicate resource: ${name}")
                    nodes[name] = [id: id, entry: entry]
                    names[id] = name
                }
            }
        }
        def resolve = { int id ->
            int pkg = (id >>> 24) & 0xff
            if (id == 0 || pkg == 1) return null // @null and framework references stay outside the app graph.
            require(pkg == 0x7f && names.containsKey(id), "Unresolved resource reference: ${hex(id)}")
            names[id]
        }
        Set<Integer> manifestIds = []
        inspect(manifest, manifestIds, [] as Set)
        Map<String, List<String>> roots = new TreeMap<>()
        manifestIds.each { id ->
            String name = resolve(id)
            if (name != null) roots.computeIfAbsent(name) { [] }.add('installed manifest')
        }
        extraRoots.toSet().sort().each { name ->
            require(nodes.containsKey(name), "Unknown explicit pinned resource: ${name}")
            roots.computeIfAbsent(name) { [] }.add('explicit pinnedResources')
        }
        Map<String, List<String>> chains = new LinkedHashMap<>()
        roots.keySet().each { chains[it] = [it] }
        List<String> pending = new ArrayList<>(roots.keySet())
        Map<String, Map> pinned = new TreeMap<>()
        for (int index = 0; index < pending.size(); index++) {
            String name = pending[index]
            def node = nodes[name]
            Resources.Entry entry = node.entry
            require(entry.configValueCount > 0, "Pinned resource has no values: ${name}")
            Set<Integer> ids = []
            Set<Resources.FileReference> files = []
            inspect(entry, ids, files)
            Map<String, String> fileHashes = new TreeMap<>()
            files.each { file ->
                require(file.path.startsWith('res/') && !file.path.split('/').any { it in ['', '.', '..'] },
                    "Unsafe linked file: ${file.path}")
                byte[] bytes = originalFile(file.path)
                require(bytes != null, "Missing resource file: ${file.path}")
                fileHashes[file.path] = sha256(bytes)
                if (file.type == Resources.FileReference.Type.PROTO_XML) {
                    byte[] xml = protoFile(file.path)
                    require(xml != null, "Missing converted XML: ${file.path}")
                    Set<Resources.FileReference> nestedFiles = []
                    inspect(Resources.XmlNode.parseFrom(xml), ids, nestedFiles)
                    require(nestedFiles.empty, "Unexpected inline file reference in XML: ${file.path}")
                } else {
                    require(file.type in [Resources.FileReference.Type.PNG, Resources.FileReference.Type.UNKNOWN],
                        "Unsupported converted resource file type: ${file.type}")
                }
            }
            List<String> dependencies = ids.collect { resolve(it) }.findAll { it != null }.unique().sort()
            dependencies.each { dependency ->
                if (!chains.containsKey(dependency)) {
                    chains[dependency] = chains[name] + dependency
                    pending.add(dependency)
                }
            }
            // Source positions are not resource semantics. Preserve every config/value and original file byte.
            Map value = normalized(entry)
            if (value.containsKey('config_value')) value.config_value.sort { JsonOutput.toJson(it) }
            pinned[name] = [name: name, id: hex(node.id), sha256: sha256(JsonOutput.toJson([value: value, files: fileHashes]).getBytes('UTF-8')),
                            configurations: entry.configValueCount, files: fileHashes, dependencies: dependencies,
                            chain: chains[name], roots: roots[name] ?: []]
        }
        // A resource-boundary snapshot deliberately does not claim the complete V1 shell contract.
        Map manifestValue = normalized(manifest)
        def attributes = manifestValue.element?.attribute
        attributes?.removeAll { it.namespace_uri == 'http://schemas.android.com/apk/res/android' && it.name in ['versionCode', 'versionCodeMajor'] }
        [version: 1, applicationId: applicationId, manifestSha256: sha256(JsonOutput.toJson(manifestValue).getBytes('UTF-8')),
         pinned: pinned.values().toList(), movable: (nodes.keySet() - pinned.keySet()).sort()]
    }

    /** Typed references only: primitive integers and reference-looking strings are not resource edges. */
    private static void inspect(Message message, Set<Integer> references, Set<Resources.FileReference> files) {
        require(message.unknownFields.asMap().isEmpty(), "Unknown AAPT2 fields in ${message.descriptorForType.fullName}")
        if (message instanceof Resources.Reference) {
            require(message.id != 0 || message.name.empty, "Unresolved symbolic reference: ${message.name}")
            references.add(message.id)
        }
        if (message instanceof Resources.XmlAttribute) references.add(message.resourceId)
        if (message instanceof Resources.FileReference) files.add(message)
        message.allFields.each { field, value ->
            if (!SOURCE_FIELDS.contains(field.name)) {
                (value instanceof List ? value : [value]).each { child ->
                    if (child instanceof Message) inspect(child, references, files)
                }
            }
        }
    }

    private static Object normalized(Object value) {
        if (value instanceof Message) {
            require(value.unknownFields.asMap().isEmpty(), "Unknown AAPT2 fields in ${value.descriptorForType.fullName}")
            Map result = new TreeMap<>()
            value.allFields.each { field, child ->
                if (!SOURCE_FIELDS.contains(field.name)) result[field.name] = normalized(child)
            }
            return result
        }
        if (value instanceof List) return value.collect { normalized(it) }
        if (value instanceof ByteString) return value.toByteArray().encodeBase64().toString()
        if (value instanceof Descriptors.EnumValueDescriptor) {
            require(value.index >= 0, 'Unknown AAPT2 enum value')
            return value.name
        }
        value
    }

    static List<String> differences(Map accepted, Map candidate) {
        require(accepted.version == 1 && accepted.applicationId == candidate.applicationId &&
            accepted.manifestSha256 ==~ /[0-9a-f]{64}/ && accepted.pinned instanceof List, 'Invalid or wrong-app resource boundary baseline')
        List<String> result = []
        if (accepted.manifestSha256 != candidate.manifestSha256) result.add('Installed manifest changed')
        Map previous = [:]
        accepted.pinned.each { entry ->
            require(entry instanceof Map && entry.name instanceof String && entry.id ==~ /0x7f[0-9a-f]{6}/ &&
                entry.sha256 ==~ /[0-9a-f]{64}/ && !previous.containsKey(entry.name), 'Invalid pinned resource baseline entry')
            previous[entry.name] = entry
        }
        Map current = candidate.pinned.collectEntries { [(it.name): it] }
        (previous.keySet() + current.keySet()).toSet().sort().each { name ->
            if (!previous.containsKey(name)) result.add("Pinned resource added: ${name}".toString())
            else if (!current.containsKey(name)) result.add("Pinned resource removed: ${name}".toString())
            else if (previous[name].id != current[name].id || previous[name].sha256 != current[name].sha256) {
                result.add("Pinned resource changed: ${name}".toString())
            }
        }
        result
    }

    private static String hex(int id) { String.format('0x%08x', id) }
    private static String sha256(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    private static void require(boolean condition, String message) {
        if (!condition) throw new GradleException("Paravoid resource boundary: ${message}")
    }
}
