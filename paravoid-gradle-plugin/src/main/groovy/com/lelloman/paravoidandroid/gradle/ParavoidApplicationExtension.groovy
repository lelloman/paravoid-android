package com.lelloman.paravoidandroid.gradle

import org.gradle.api.file.DirectoryProperty

abstract class ParavoidApplicationExtension {
    /** Optional, read-only input: <directory>/<variant>/resource-ledger.json. */
    abstract DirectoryProperty getBaselineDirectory()
}
