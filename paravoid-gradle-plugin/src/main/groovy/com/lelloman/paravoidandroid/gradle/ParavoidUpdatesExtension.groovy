package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty

abstract class ParavoidUpdatesExtension {
    ParavoidUpdatesExtension() {
        enabled.convention(false); baseUrl.convention(''); channel.convention('stable')
        authentication.convention('public'); debugHttpAllowed.convention(false)
    }
    abstract Property<Boolean> getEnabled()
    abstract Property<String> getBaseUrl()
    abstract Property<String> getChannel()
    abstract Property<String> getAuthentication()
    abstract Property<Boolean> getDebugHttpAllowed()
    abstract RegularFileProperty getTrustPolicyFile()
}
