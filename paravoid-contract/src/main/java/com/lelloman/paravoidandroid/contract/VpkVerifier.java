package com.lelloman.paravoidandroid.contract;

import java.io.File;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Reads caller-owned stable bytes; never extracts, writes state, admits or executes. */
public interface VpkVerifier {
    VerifiedRelease verifyDownloaded(File archive, ShellPolicy policy, RequestScope device,
        ExpectedArchive expected) throws ContractException;
    VerifiedRelease verifyEmbedded(File archive, ShellPolicy policy, RequestScope device) throws ContractException;
    /** Rechecks accepted bytes offline; no fresh head or credential is required. */
    VerifiedRelease verifyRetained(File archive, ShellPolicy policy, RequestScope device,
        ExpectedArchive acceptedIdentity) throws ContractException;
}
