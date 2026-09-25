package com.lelloman.paravoidandroid.updates;

import java.util.*;
/** Immutable, serializable scheduling preferences. Times are seconds, not exact execution promises. */
public final class UpdateSchedule {
    public final long intervalSeconds, flexSeconds, retrySeconds, maxRetrySeconds;
    public final int maxRetries;
    public final boolean checks, downloads, checkUnmetered, downloadUnmetered, charging, batteryNotLow, deviceIdle;
    public final Map<String,String> policyData;
    public UpdateSchedule(long interval, long flex, boolean checks, boolean downloads,
            boolean checkUnmetered, boolean downloadUnmetered, boolean charging, boolean batteryNotLow,
            boolean deviceIdle, long retry, long maxRetry, int maxRetries, Map<String,String> data) {
        if(interval<900 || interval>365L*86400 || flex<300 || flex>interval || retry<30 || maxRetry<retry
                || maxRetry>3600 || maxRetries<0 || maxRetries>10 || data.size()>32) throw new IllegalArgumentException("Invalid update schedule");
        intervalSeconds=interval; flexSeconds=flex; this.checks=checks; this.downloads=downloads;
        this.checkUnmetered=checkUnmetered; this.downloadUnmetered=downloadUnmetered;
        this.charging=charging; this.batteryNotLow=batteryNotLow; this.deviceIdle=deviceIdle;
        retrySeconds=retry; maxRetrySeconds=maxRetry; this.maxRetries=maxRetries;
        for(Map.Entry<String,String> e:data.entrySet()) if(e.getKey()==null || e.getValue()==null
                || e.getKey().length()>128 || e.getValue().length()>1024) throw new IllegalArgumentException("Invalid policy data");
        policyData=Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
    public static UpdateSchedule defaults() { return new UpdateSchedule(21600,3600,true,true,false,true,false,false,false,30,3600,3,Collections.emptyMap()); }
    public UpdateSchedule preferences(boolean checks, boolean downloads, boolean unmetered) {
        return new UpdateSchedule(intervalSeconds,flexSeconds,checks,downloads,checkUnmetered,unmetered,
            charging,batteryNotLow,deviceIdle,retrySeconds,maxRetrySeconds,maxRetries,policyData);
    }
}
