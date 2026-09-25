package com.lelloman.paravoidandroid.updates;

import java.io.OutputStream;
/** Shell-packaged transport only; verification, storage and activation belong to the shell. */
public interface UpdateUpdater {
    /** Write exactly archiveSize bytes, starting at zero. Do not close destination.
     * Implementations may use privateDirectory for resumable transport caches across attempts. */
    void download(UpdateRequest request, UpdateOffer offer, OutputStream destination,
                  Cancellation cancellation) throws Exception;
}
