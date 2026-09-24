package com.lelloman.paravoidandroid.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.model.ObjectFactory
import org.gradle.api.Action
import javax.inject.Inject

abstract class ParavoidApplicationExtension {
    final ParavoidUpdatesExtension updates
    final ParavoidSigningExtension signing
    @Inject ParavoidApplicationExtension(ObjectFactory objects) {
        updates = objects.newInstance(ParavoidUpdatesExtension)
        signing = objects.newInstance(ParavoidSigningExtension)
        bootstrap.convention('embedded')
        packaging.convention('dexOnly')
        controlsLauncher.convention(true)
        minifyPayload.convention(false)
        releaseId.convention(payloadVersion.map { 'p' + it })
    }
    abstract Property<String> getBootstrap()
    abstract Property<String> getPackaging()
    /** Complete-profile shell launcher entry independent of dynamic shortcut support. */
    abstract Property<Boolean> getControlsLauncher()
    /** Opt in to R8 shrinking and obfuscation of the separated payload DEX. */
    abstract Property<Boolean> getMinifyPayload()
    /** Extra R8 rules for reflection and dependency-specific entry points. */
    abstract ConfigurableFileCollection getPayloadProguardFiles()
    abstract Property<Long> getPayloadVersion()
    abstract Property<String> getReleaseId()
    void updates(Action<? super ParavoidUpdatesExtension> action) { action.execute(updates) }
    void signing(Action<? super ParavoidSigningExtension> action) { action.execute(signing) }
    /** Optional, read-only input: <directory>/<variant>/resource-ledger.json. */
    abstract DirectoryProperty getBaselineDirectory()
    /** Additional local type/name roots used by consumers outside the payload process. */
    abstract ListProperty<String> getPinnedResources()
}
