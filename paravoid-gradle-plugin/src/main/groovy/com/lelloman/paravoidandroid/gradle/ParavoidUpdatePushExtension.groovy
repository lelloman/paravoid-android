package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty

abstract class ParavoidUpdatePushExtension {
    ParavoidUpdatePushExtension() {
        enabled.convention(false); webSocketUrl.convention(''); transportClass.convention('')
        authenticationClass.convention(''); componentClasses.convention([]); behavior.convention('prompt')
    }
    abstract Property<Boolean> getEnabled()
    abstract Property<String> getWebSocketUrl()
    abstract Property<String> getTransportClass()
    abstract Property<String> getAuthenticationClass()
    abstract ListProperty<String> getComponentClasses()
    abstract Property<String> getBehavior()
}
