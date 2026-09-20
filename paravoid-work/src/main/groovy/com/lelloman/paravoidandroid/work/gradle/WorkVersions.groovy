package com.lelloman.paravoidandroid.work.gradle

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

class WorkVersions {
    static final String SUPPORTED = '2.10.1'
    static Map<String, String> from(ResolvedComponentResult root) {
        Map<String, String> versions = [:]
        Set<Object> visited = new HashSet<>()
        def pending = new ArrayDeque<ResolvedComponentResult>()
        pending.add(root)
        while (!pending.empty) {
            def component = pending.removeFirst()
            if (!visited.add(component.id)) continue
            def id = component.id
            if (id instanceof ModuleComponentIdentifier && id.group == 'androidx.work' && id.module == 'work-runtime') {
                versions[id.module] = id.version
            }
            component.dependencies.each { edge ->
                if (edge instanceof ResolvedDependencyResult) pending.add(edge.selected)
            }
        }
        return versions
    }
    static void validate(Map<String, String> versions) {
        if (versions['work-runtime'] != SUPPORTED) {
            throw new GradleException("paravoid-work supports WorkManager ${SUPPORTED}; resolved work-runtime: ${versions['work-runtime'] ?: 'missing'}. Use the supported runtime or remove the optional integration.")
        }
    }
}
