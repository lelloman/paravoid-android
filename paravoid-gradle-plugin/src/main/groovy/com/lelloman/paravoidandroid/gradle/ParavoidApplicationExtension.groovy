package com.lelloman.paravoidandroid.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

abstract class ParavoidApplicationExtension {
    /** Optional, read-only input: <directory>/<variant>/resource-ledger.json. */
    abstract DirectoryProperty getBaselineDirectory()
    /** Additional local type/name roots used by consumers outside the payload process. */
    abstract ListProperty<String> getPinnedResources()
}
