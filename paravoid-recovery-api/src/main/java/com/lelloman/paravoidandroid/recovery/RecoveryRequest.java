package com.lelloman.paravoidandroid.recovery;

import java.io.File;
import java.util.*;

/** Installed request scope. Bearer credentials are short-lived in-memory values:
 * never log/persist them or send them outside baseUrl's configured origin/path. */
public final class RecoveryRequest {
    public final String applicationId, shellContractId, channel, baseUrl;
    public final int sdk, runtimeAbi;
    public final List<String> abis;
    public final File privateDirectory;
    private final String bearer;
    public RecoveryRequest(String app, String contract, String channel, String baseUrl,
            int sdk, int runtimeAbi, List<String> abis, File directory, String bearer) {
        this.applicationId=app; this.shellContractId=contract; this.channel=channel;
        this.baseUrl=baseUrl; this.sdk=sdk; this.runtimeAbi=runtimeAbi;
        this.abis=Collections.unmodifiableList(new ArrayList<>(abis));
        this.privateDirectory=directory; this.bearer=bearer;
    }
    public String authorizationHeader() { return bearer == null ? null : "Bearer " + bearer; }
    @Override public String toString() { return "RecoveryRequest[" + applicationId + ", credentials redacted]"; }
}
