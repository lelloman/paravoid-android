package com.lelloman.paravoidandroid.gradle
import org.gradle.api.provider.Property
abstract class ParavoidUpdateScheduleExtension {
    ParavoidUpdateScheduleExtension() {
        intervalSeconds.convention(21600L); flexSeconds.convention(3600L)
        checks.convention(true); downloads.convention(true); checkUnmetered.convention(false); downloadUnmetered.convention(true)
        charging.convention(false); batteryNotLow.convention(false); deviceIdle.convention(false)
        retrySeconds.convention(30L); maxRetrySeconds.convention(3600L); maxRetries.convention(3)
    }
    abstract Property<Long> getIntervalSeconds()
    abstract Property<Long> getFlexSeconds()
    abstract Property<Boolean> getChecks()
    abstract Property<Boolean> getDownloads()
    abstract Property<Boolean> getCheckUnmetered()
    abstract Property<Boolean> getDownloadUnmetered()
    abstract Property<Boolean> getCharging()
    abstract Property<Boolean> getBatteryNotLow()
    abstract Property<Boolean> getDeviceIdle()
    abstract Property<Long> getRetrySeconds()
    abstract Property<Long> getMaxRetrySeconds()
    abstract Property<Integer> getMaxRetries()
}
