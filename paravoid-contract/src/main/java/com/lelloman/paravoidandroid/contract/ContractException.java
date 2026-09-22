package com.lelloman.paravoidandroid.contract;

/** Codes are integration API; messages must never include credentials or input bodies. */
public final class ContractException extends Exception {
    public enum Code {
        MALFORMED, LIMIT_EXCEEDED, UNTRUSTED_KEY, INVALID_SIGNATURE, INCOMPATIBLE,
        INTEGRITY, EXPIRED, CLOCK_INVALID, REPLAY, IDENTITY_CONFLICT,
        CREDENTIAL_UNAVAILABLE, CREDENTIAL_CHANGED, STALE_ADMISSION,
        CORRUPT_STATE, INSUFFICIENT_STORAGE, IO, CANCELLED, UNAVAILABLE
    }
    public final Code code;
    public ContractException(Code code, String safeMessage) { super(safeMessage); this.code = code; }
    // Do not attach parser/network causes: their messages may contain credentials.
}
