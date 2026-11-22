package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property

abstract class ParavoidCrashRecoveryExtension {
    ParavoidCrashRecoveryExtension() {
        enabled.convention(false); updater.convention('default'); providerClass.convention(''); actionsClass.convention('')
    }
    abstract Property<Boolean> getEnabled()
    abstract Property<String> getUpdater()
    abstract Property<String> getProviderClass()
    /**
     * Optional RecoveryActionProvider from paravoidRecoveryImplementation that adds buttons, such
     * as "Report bug", to the crash recovery screen.
     */
    abstract Property<String> getActionsClass()
}
