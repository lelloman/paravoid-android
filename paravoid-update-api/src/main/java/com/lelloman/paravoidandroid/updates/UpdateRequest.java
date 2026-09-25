package com.lelloman.paravoidandroid.updates;

import java.io.File;
import java.util.*;
/** Installed scope. Do not log credentials or forward them outside baseUrl's origin/path. */
public final class UpdateRequest {
    public final String applicationId, shellContractId, channel, baseUrl, metadataUrl, payloadUrlTemplate;
    public final int sdk, runtimeAbi;
    public final List<String> abis;
    public final File privateDirectory;
    private final String authorization;
    public UpdateRequest(String app, String contract, String channel, String base, String metadata,
            String payload, int sdk, int runtime, List<String> abis, File directory, String authorization) {
        this.applicationId=app; this.shellContractId=contract; this.channel=channel; this.baseUrl=base;
        this.metadataUrl=metadata; this.payloadUrlTemplate=payload; this.sdk=sdk; this.runtimeAbi=runtime;
        this.abis=Collections.unmodifiableList(new ArrayList<>(abis)); this.privateDirectory=directory;
        this.authorization=authorization;
    }
    public String authorizationHeader() { return authorization; }
    @Override public String toString() { return "UpdateRequest["+applicationId+", credentials redacted]"; }
}
