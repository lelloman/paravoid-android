package com.lelloman.paravoidandroid.contract;

import java.io.File;
import com.lelloman.paravoidandroid.contract.Protocol.StageResult;

/**
 * Exclusive update-space admission, held from before archive transfer until staging returns.
 * Not physical preallocation: unrelated writers can still exhaust the filesystem.
 * Thread-confined, single-use for staging; close on every exit (including cancellation).
 * Implementations must release exclusion on process death without resetting security state.
 */
public interface DownloadReservation extends AutoCloseable {
    /** Borrows the complete archive synchronously; does not select or execute it. */
    StageResult stage(File archive) throws ContractException;
    @Override void close() throws ContractException;
}
