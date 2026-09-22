package com.lelloman.paravoidandroid.contract;

import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Trusted local APK authority, never implemented with a download or caller-supplied cached DTO. */
public interface InstalledStateSource {
    final class Snapshot {
        public final String fileIdentity;
        public final ShellPolicy policy;
        public final CredentialScope credential; // null denies updates, not offline execution
        public Snapshot(String fileIdentity, ShellPolicy policy, CredentialScope credential) {
            this.fileIdentity = java.util.Objects.requireNonNull(fileIdentity);
            this.policy = java.util.Objects.requireNonNull(policy); this.credential = credential;
        }
    }
    /** Potentially reads/verifies APK policy/grant. Must execute outside selection locks. */
    Snapshot read() throws ContractException;
    /** Quick installed-file identity check; no archive parsing or signature verification here. */
    boolean isCurrent(Snapshot snapshot) throws ContractException;
}
