package com.lelloman.paravoidandroid.contract;

import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Stateless authenticity/schema/scope checks. C applies clocks and durable replay policy. */
public interface MetadataVerifier {
    VerifiedHead verifyHead(byte[] envelope, ShellPolicy policy, RequestScope scope) throws ContractException;
    VerifiedGrant verifyGrant(byte[] envelope, ShellPolicy policy) throws ContractException;
    TrustPolicy readTrustPolicy(byte[] bytes) throws ContractException;
}
