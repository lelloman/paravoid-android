package com.lelloman.paravoidandroid.gradle

import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.Action
import javax.inject.Inject

abstract class ParavoidUpdatesExtension {
    final ParavoidUpdatePushExtension push
    final ParavoidUpdateScheduleExtension schedule
    @Inject ParavoidUpdatesExtension(ObjectFactory objects) {
        push=objects.newInstance(ParavoidUpdatePushExtension)
        schedule=objects.newInstance(ParavoidUpdateScheduleExtension)
        restartBehavior.convention('manual'); mode.convention('api'); metadataUrl.convention(''); payloadUrlTemplate.convention('')
        checkerClass.convention(''); updaterClass.convention(''); policyClass.convention(''); jobIdBase.convention(0x50560000)
        enabled.convention(false); baseUrl.convention(''); channel.convention('stable')
        authentication.convention('public'); debugHttpAllowed.convention(false)
    }
    void push(Action<? super ParavoidUpdatePushExtension> action) { action.execute(push) }
    void schedule(Action<? super ParavoidUpdateScheduleExtension> action) { action.execute(schedule) }
    Map<String,String> configuration() {
        Map<String,String> values=[:]
        ['mode','metadataUrl','payloadUrlTemplate','checkerClass','updaterClass','policyClass','jobIdBase','restartBehavior'].each { values[it]=this."$it".get().toString() }
        ['intervalSeconds','flexSeconds','checks','downloads','checkUnmetered','downloadUnmetered','charging','batteryNotLow','deviceIdle','retrySeconds','maxRetrySeconds','maxRetries'].each { values[it]=schedule."$it".get().toString() }
        values.putAll([pushEnabled:push.enabled.get().toString(), pushBackgroundConnection:push.backgroundConnection.get().toString(), pushWebSocketUrl:push.webSocketUrl.get(),
            pushTransportClass:push.transportClass.get(),pushAuthenticationClass:push.authenticationClass.get(),
            pushComponentClasses:push.componentClasses.get().join(';'),updateBehavior:push.enabled.get() ? push.behavior.get() : 'automatic'])
        values
    }
    abstract Property<String> getRestartBehavior()
    abstract Property<String> getMode()
    abstract Property<String> getMetadataUrl()
    abstract Property<String> getPayloadUrlTemplate()
    abstract Property<String> getCheckerClass()
    abstract Property<String> getUpdaterClass()
    abstract Property<String> getPolicyClass()
    abstract Property<Integer> getJobIdBase()
    abstract Property<Boolean> getEnabled()
    abstract Property<String> getBaseUrl()
    abstract Property<String> getChannel()
    abstract Property<String> getAuthentication()
    abstract Property<Boolean> getDebugHttpAllowed()
    abstract RegularFileProperty getTrustPolicyFile()
}
