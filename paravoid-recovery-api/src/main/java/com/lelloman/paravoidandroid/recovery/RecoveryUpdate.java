package com.lelloman.paravoidandroid.recovery;

/** Immutable description of an offer verified by the shell. No staging authority. */
public final class RecoveryUpdate {
    public final String releaseId, manifestSha256, archiveSha256;
    public final long payloadVersion, archiveSize;
    public RecoveryUpdate(String id, long version, String manifest, String hash, long size) {
        releaseId=id; payloadVersion=version; manifestSha256=manifest; archiveSha256=hash; archiveSize=size;
    }
}
