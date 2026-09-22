package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty

abstract class ParavoidSigningExtension {
    abstract Property<String> getKeyId()
    abstract RegularFileProperty getPrivateKeyFile()
}
