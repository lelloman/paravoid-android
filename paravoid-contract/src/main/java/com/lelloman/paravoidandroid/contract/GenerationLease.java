package com.lelloman.paravoidandroid.contract;

import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Held for the entire payload process lifetime, not just classloader construction. */
public interface GenerationLease {
    VerifiedRelease release();
    GenerationFiles files();
    /** Durable trial marking must precede any user constructor/provider execution. */
    void beforeUserCode() throws ContractException;
    void applicationCreated() throws ContractException;
    /** UI healthy only after onCreate and the first resumed Activity's first frame. */
    void firstFrameRendered() throws ContractException;
    void startupFailed(ContractException.Code safeReason) throws ContractException;
    // Deliberately no close(): cannot safely release while any payload code remains in this process.
}
