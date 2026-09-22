package com.lelloman.paravoidandroid.contract;

import java.io.File;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Implemented by Track C. Calls can block on disk work; delivery invokes them off the UI thread. */
public interface Lifecycle {
    /** Current installed grant binding; null means denied updates, never public fallback. */
    void setCredentialScope(CredentialScope scope) throws ContractException;
    /** Atomically compares current credential binding, freshness and authenticated history. */
    AdmissionResult observeHead(VerifiedHead head, CredentialScope requestCredential) throws ContractException;
    /** Borrows the path until return. Never modifies/deletes it. Copies and verifies privately. */
    StageResult stageDownloaded(File completedArchive, AdmissionId admission) throws ContractException;
    StageResult stageEmbedded(File embeddedArchive) throws ContractException;
    LifecycleSnapshot snapshot() throws ContractException;
    /** Bootstrap/recovery processes must not call this. Throws UNAVAILABLE when no runnable generation. */
    GenerationLease acquireForProcess() throws ContractException;
    /** Requires an explicit user confirmation; no version downgrade authorization. */
    void retryQuarantined(ExpectedArchive release) throws ContractException;
    void setRetainedPrevious(int count) throws ContractException; // 0..3
}
