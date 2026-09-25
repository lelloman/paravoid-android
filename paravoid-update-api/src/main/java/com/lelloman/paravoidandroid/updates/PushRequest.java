package com.lelloman.paravoidandroid.updates;

import java.io.File;
import java.util.*;
/** Installed subscription scope. Transport implementations own vendor registration, if any. */
public final class PushRequest {
    public final String applicationId, shellContractId, channel, endpoint;
    public final File privateDirectory;
    public final Map<String,String> headers;
    public PushRequest(String app,String contract,String channel,String endpoint,File directory,Map<String,String> headers) {
        applicationId=app; shellContractId=contract; this.channel=channel; this.endpoint=endpoint; privateDirectory=directory;
        this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
    @Override public String toString() { return "PushRequest["+applicationId+", credentials redacted]"; }
}
