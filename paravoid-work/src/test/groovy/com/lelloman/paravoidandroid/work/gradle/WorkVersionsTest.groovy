package com.lelloman.paravoidandroid.work.gradle

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.junit.Test
import static org.junit.Assert.*

class WorkVersionsTest {
    @Test void acceptsPinnedVersion() { WorkVersions.validate(['work-runtime': '2.10.1']) }
    @Test void rejectsMissingAndUnsupportedVersions() {
        [[:], ['work-runtime': '2.10.0']].each { versions ->
            assertTrue(assertThrows(GradleException, { WorkVersions.validate(versions) }).message
                .contains('paravoid-work supports WorkManager 2.10.1'))
        }
    }
    @Test void resolvesTransitiveSelectionAndCycles() {
        Set edges = [] as Set
        def root = component('fixture', 'app', '1', edges)
        def runtime = component('androidx.work', 'work-runtime', '2.10.0', [edge(root)] as Set)
        edges.add(edge(component('fixture', 'wrapper', '1', [edge(runtime)] as Set)))
        edges.add(edge(component('other', 'work-runtime', '99', [] as Set)))
        assertEquals(['work-runtime': '2.10.0'], WorkVersions.from(root))
    }
    private static ResolvedComponentResult component(String group, String name, String version, Set edges) {
        def id = [getGroup: { group }, getModule: { name }, getVersion: { version }] as ModuleComponentIdentifier
        return [getId: { id }, getDependencies: { edges }] as ResolvedComponentResult
    }
    private static ResolvedDependencyResult edge(ResolvedComponentResult target) {
        return [getSelected: { target }] as ResolvedDependencyResult
    }
}
