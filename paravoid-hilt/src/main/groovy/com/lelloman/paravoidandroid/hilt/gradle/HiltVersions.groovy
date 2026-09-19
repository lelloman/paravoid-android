package com.lelloman.paravoidandroid.hilt.gradle

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

class HiltVersions {
    static final String SUPPORTED = '2.57.2'
    static final List<String> MODULES = ['hilt-android', 'hilt-core']

    /** Inspect selected versions, not requests that constraints/substitution may override. */
    static Map<String, String> from(ResolvedComponentResult root) {
        Map<String, String> result = [:]
        Set<Object> visited = new HashSet<>()
        def pending = new ArrayDeque<ResolvedComponentResult>()
        pending.add(root)
        while (!pending.isEmpty()) {
            def component = pending.removeFirst()
            if (!visited.add(component.id)) continue
            def id = component.id
            if (id instanceof ModuleComponentIdentifier && id.group == 'com.google.dagger' && MODULES.contains(id.module)) {
                result[id.module] = id.version
            }
            component.dependencies.each { dependency ->
                if (dependency instanceof ResolvedDependencyResult) pending.add(dependency.selected)
            }
        }
        return result
    }

    static void validate(Map<String, String> versions) {
        MODULES.each { module ->
            if (versions[module] != SUPPORTED) {
                throw new GradleException("paravoid-hilt supports Hilt ${SUPPORTED}; resolved ${module}: ${versions[module] ?: 'missing'}. Use the supported Hilt runtime/compiler/plugin versions, or remove the optional integration.")
            }
        }
    }
}
