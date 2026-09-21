package com.lelloman.paravoidandroid.gradle

import com.android.aapt.Resources
import org.gradle.api.GradleException

/** Filters an already linked table. Never reassigns package/type/entry IDs or configurations. */
final class ResourceTableSubset {
    static Resources.ResourceTable retain(Resources.ResourceTable table, Set<String> names) {
        Set<String> found = []
        def result = table.toBuilder().clearPackage()
        table.packageList.each { pkg ->
            def subsetPackage = pkg.toBuilder().clearType()
            pkg.typeList.each { type ->
                def subsetType = type.toBuilder().clearEntry()
                type.entryList.each { entry ->
                    String name = "${type.name}/${entry.name}"
                    if (names.contains(name)) {
                        if (!found.add(name)) throw new GradleException("Duplicate resource selected for shell: ${name}")
                        subsetType.addEntry(entry)
                    }
                }
                if (subsetType.entryCount > 0) subsetPackage.addType(subsetType)
            }
            result.addPackage(subsetPackage)
        }
        if (found != names) throw new GradleException("Unknown resources selected for shell: ${(names - found).sort()}")
        result.build()
    }
}
