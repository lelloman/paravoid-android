package com.lelloman.paravoidandroid.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class CompletePolicyTaskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()

    @Test void releaseForbidsDebugHttpEvenWhenBuildTypeIsDebuggable() {
        def project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        def task = project.tasks.create('policy', GenerateCompletePolicyTask)
        task.bootstrap.set('embedded'); task.authentication.set('public')
        task.releaseBuild.set(true); task.debugHttpAllowed.set(true); task.debuggable.set(true)
        assertTrue(assertThrows(GradleException) { task.generate() }.message.contains('Release output forbids debug HTTP'))
    }

    @Test void invalidPolicyEnumsFailBeforeReadingSigningInputs() {
        def project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        def task = project.tasks.create('policy', GenerateCompletePolicyTask)
        task.bootstrap.set('other'); task.authentication.set('public')
        assertTrue(assertThrows(GradleException) { task.generate() }.message.contains('bootstrap embedded/empty'))
    }
}
