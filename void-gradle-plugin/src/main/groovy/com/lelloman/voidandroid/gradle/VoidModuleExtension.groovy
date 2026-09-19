package com.lelloman.voidandroid.gradle

import org.gradle.api.provider.Property

abstract class VoidModuleExtension {
    abstract Property<String> getEntryPoint()
}
