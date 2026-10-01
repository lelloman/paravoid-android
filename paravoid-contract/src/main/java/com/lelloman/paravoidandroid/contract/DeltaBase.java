package com.lelloman.paravoidandroid.contract;

import java.io.IOException;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/** Read-only archive descriptor, not an execution lease. Close before staging/ending the reservation. */
public interface DeltaBase extends AutoCloseable {
    ExpectedArchive identity();
    /** Positional read; bytes remain readable even if cleanup unlinks the owned generation. */
    int read(long position, byte[] buffer, int offset, int length) throws IOException;
    @Override void close() throws IOException;
}
