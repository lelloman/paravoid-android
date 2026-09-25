package com.lelloman.paravoidandroid.updates;

/** An immutable archive identity selected from verified discovery metadata. No staging authority. */
public final class UpdateOffer {
    public final String releaseId, manifestSha256, archiveSha256;
    public final long payloadVersion, archiveSize;
    public UpdateOffer(String id, long version, String manifest, String archive, long size) {
        releaseId=id; payloadVersion=version; manifestSha256=manifest; archiveSha256=archive; archiveSize=size;
    }
}
