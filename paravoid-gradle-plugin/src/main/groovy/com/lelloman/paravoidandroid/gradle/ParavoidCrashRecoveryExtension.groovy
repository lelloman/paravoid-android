package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property

abstract class ParavoidCrashRecoveryExtension {
    ParavoidCrashRecoveryExtension() {
        enabled.convention(false); updater.convention('default'); providerClass.convention('')
    }
    abstract Property<Boolean> getEnabled()
    abstract Property<String> getUpdater()
    abstract Property<String> getProviderClass()
}
