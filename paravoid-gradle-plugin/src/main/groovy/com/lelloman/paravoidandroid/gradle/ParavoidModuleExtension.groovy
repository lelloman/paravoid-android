package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property

abstract class ParavoidModuleExtension {
    abstract Property<String> getEntryPoint()
}
