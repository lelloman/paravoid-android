package com.lelloman.paravoidandroid.hilt.gradle

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.junit.Test
import static org.junit.Assert.*

class HiltVersionsTest {
    @Test void acceptsSupportedRuntimeVersions() {
        HiltVersions.validate(['hilt-android': '2.57.2', 'hilt-core': '2.57.2'])
    }

    @Test void rejectsMissingOrMismatchedRuntimeVersions() {
        [[:], ['hilt-android': '2.57.2'], ['hilt-android': '2.57.2', 'hilt-core': '2.60.1']].each { versions ->
            def error = assertThrows(GradleException, { HiltVersions.validate(versions) })
            assertTrue(error.message.contains('paravoid-hilt supports Hilt 2.57.2'))
        }
    }

    @Test void readsSelectedTransitiveVersionsAndHandlesCycles() {
        Set edges = [] as Set
        def root = component('fixture', 'app', '1', edges)
        def core = component('com.google.dagger', 'hilt-core', '2.60.1', [] as Set)
        def android = component('com.google.dagger', 'hilt-android', '2.57.2', [edge(core), edge(root)] as Set)
        edges.add(edge(android))
        edges.add(edge(component('other.group', 'hilt-core', '99', [] as Set)))
        assertEquals(['hilt-android': '2.57.2', 'hilt-core': '2.60.1'], HiltVersions.from(root))
    }

    private static ResolvedComponentResult component(String group, String name, String version, Set dependencies) {
        def id = [getGroup: { group }, getModule: { name }, getVersion: { version }] as ModuleComponentIdentifier
        return [getId: { id }, getDependencies: { dependencies }] as ResolvedComponentResult
    }
    private static ResolvedDependencyResult edge(ResolvedComponentResult target) {
        return [getSelected: { target }] as ResolvedDependencyResult
    }
}
